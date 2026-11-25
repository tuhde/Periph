package ioexpander

import (
	"errors"
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCA6416A register addresses.
const (
	// Port-0 register addresses; port 1 is +1. A two-byte transfer at a
	// port-0 register toggles to port 1 (register-pair layout).
	pca6416aRegInput    uint8 = 0x00
	pca6416aRegOutput   uint8 = 0x02
	pca6416aRegPolarity uint8 = 0x04
	pca6416aRegConfig   uint8 = 0x06
)

// Pin modes accepted by (PCA6416APin).Mode.
const (
	// PCA6416AInput configures the pin as a high-impedance input.
	PCA6416AInput PinMode = iota
	// PCA6416AOutput configures the pin as a push-pull output.
	PCA6416AOutput
	// PCA6416AInputPullup is rejected: the chip has no pull-up resistors.
	PCA6416AInputPullup
)

// ErrPCA6416ANoPullup is returned for any request to enable a pull-up: the
// PCA6416A has no pull-up resistors.
var ErrPCA6416ANoPullup = errors.New("pca6416a: no pull-up resistors")

// PCA6416AMinimal is the PCA6416A (NXP) 16-bit I/O expander driver — minimal
// interface. I²C / SMBus (no PEC).
//
// All sixteen pins (0–7 = P0_0–P0_7, 8–15 = P1_0–P1_7) are bidirectional.
// Direction is controlled per pin via CONFIG (1 = input, 0 = output); 16-bit
// shadows of OUTPUT and CONFIG make a single-pin change one register write. The
// chip has no internal pull-ups. The registers form pairs: a two-byte transfer
// starting at a port-0 register covers both ports.
type PCA6416AMinimal struct {
	conn      connection.RegisterConnection
	shadow    uint16 // shadow of OUTPUT0/1
	direction uint16 // shadow of CONFIG0/1 (1 = input)
	polarity  uint16 // shadow of POLARITY0/1
	lastInput uint16 // last INPUT0/1 value read (interrupt reference)
}

// NewPCA6416AMinimal creates a PCA6416A driver and runs the initialisation
// sequence: OUTPUT ← 0xFF, POLARITY ← 0x00, CONFIG ← 0xFF on both ports (all
// inputs), then INPUT0/1 is read to initialise the cached input value.
//
// conn must be a configured I²C or SMBus (no PEC) connection bound to the
// device's 7-bit address (0x20 with ADDR = VSS, 0x21 with ADDR = VDD(P)); addr
// is informational.
func NewPCA6416AMinimal(conn connection.RegisterConnection, addr uint8) (*PCA6416AMinimal, error) {
	_ = addr
	d := &PCA6416AMinimal{conn: conn, shadow: 0xFFFF, direction: 0xFFFF}
	if err := d.write16(pca6416aRegOutput, 0xFFFF); err != nil {
		return nil, err
	}
	if err := d.write16(pca6416aRegPolarity, 0x0000); err != nil {
		return nil, err
	}
	if err := d.write16(pca6416aRegConfig, 0xFFFF); err != nil {
		return nil, err
	}
	v, err := d.read16(pca6416aRegInput)
	if err != nil {
		return nil, err
	}
	d.lastInput = v
	return d, nil
}

func (d *PCA6416AMinimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *PCA6416AMinimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

// read16 reads both ports of a register pair in one two-byte transfer starting
// at the port-0 register (port 0 first, then port 1).
func (d *PCA6416AMinimal) read16(base uint8) (uint16, error) {
	buf, err := d.conn.ReadReg(uint32(base), 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0]) | uint16(buf[1])<<8, nil
}

func (d *PCA6416AMinimal) write16(base uint8, value uint16) error {
	return d.conn.WriteReg(uint32(base), []byte{byte(value), byte(value >> 8)})
}

// ReadPort reads one 8-pin port via its INPUT register (actual pin levels).
// port is 0 (P0_0–P0_7) or 1 (P1_0–P1_7); only bit 0 is used.
func (d *PCA6416AMinimal) ReadPort(port uint8) (uint8, error) {
	return d.readReg(pca6416aRegInput + (port & 1))
}

// WritePort writes all 8 output bits of one port via OUTPUTx and updates the
// shadow. Bits of input pins are stored but have no effect until the pin
// becomes an output. port is 0 or 1; only bit 0 is used.
func (d *PCA6416AMinimal) WritePort(port uint8, mask uint8) error {
	port &= 1
	shift := 8 * uint16(port)
	d.shadow = d.shadow&^(0xFF<<shift) | uint16(mask)<<shift
	return d.writeReg(pca6416aRegOutput+port, mask)
}

// Pin returns a Pin proxy for pin n (0–15).
func (d *PCA6416AMinimal) Pin(n uint8) PCA6416APin {
	return PCA6416APin{chip: d, n: n & 15}
}

func (d *PCA6416AMinimal) setPin(n uint8, high bool) error {
	if high {
		d.shadow |= 1 << n
	} else {
		d.shadow &^= 1 << n
	}
	return d.writeReg(pca6416aRegOutput+n>>3, uint8(d.shadow>>(8*(n>>3))))
}

// setDirection writes the pin's OUTPUT bit before clearing its CONFIG bit so the
// pin never glitches to a stale level.
func (d *PCA6416AMinimal) setDirection(n uint8, input bool) error {
	port := n >> 3
	if input {
		d.direction |= 1 << n
	} else {
		if err := d.writeReg(pca6416aRegOutput+port, uint8(d.shadow>>(8*port))); err != nil {
			return err
		}
		d.direction &^= 1 << n
	}
	return d.writeReg(pca6416aRegConfig+port, uint8(d.direction>>(8*port)))
}

// PCA6416APin is a GPIO proxy for a single pin. It satisfies
// connection.OutputPin (Set), so an output pin can be used directly as the EN
// pin of another chip's connection.
//
// Obtained via (*PCA6416AMinimal).Pin.
type PCA6416APin struct {
	chip *PCA6416AMinimal
	n    uint8
}

// Mode sets the pin direction. PCA6416AInputPullup is rejected with
// ErrPCA6416ANoPullup and nothing is written.
func (p PCA6416APin) Mode(m PinMode) error {
	if m == PCA6416AInputPullup {
		return ErrPCA6416ANoPullup
	}
	return p.chip.setDirection(p.n, m != PCA6416AOutput)
}

// Write sets the output latch to high (true) or low (false).
func (p PCA6416APin) Write(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Set is Write, satisfying connection.OutputPin.
func (p PCA6416APin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Read reads the actual logic level at the pin via the INPUT register.
func (p PCA6416APin) Read() (bool, error) {
	v, err := p.chip.readReg(pca6416aRegInput + p.n>>3)
	if err != nil {
		return false, err
	}
	return (v>>(p.n&7))&1 == 1, nil
}

// Toggle inverts the output latch bit for this pin.
func (p PCA6416APin) Toggle() error {
	return p.chip.setPin(p.n, (p.chip.shadow>>p.n)&1 == 0)
}

type pca6416aWatch struct {
	trigger connection.Trigger
	handler func(PCA6416AFullPin)
}

// PCA6416AFull is the PCA6416A driver — full interface. Extends PCA6416AMinimal
// with polarity inversion, whole-device direction, change-based interrupts and raw
// register access.
type PCA6416AFull struct {
	*PCA6416AMinimal

	mu          sync.Mutex
	level       uint16
	callback    func(uint16)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	watches     [16]*pca6416aWatch
}

// NewPCA6416AFull creates a PCA6416A full driver with the same initialisation as
// NewPCA6416AMinimal.
func NewPCA6416AFull(conn connection.RegisterConnection, addr uint8) (*PCA6416AFull, error) {
	m, err := NewPCA6416AMinimal(conn, addr)
	if err != nil {
		return nil, err
	}
	return &PCA6416AFull{PCA6416AMinimal: m, level: m.lastInput}, nil
}

// ReadAll reads both INPUT registers in one two-byte transaction; bit n = pin n
// (bits 0–7 port 0, 8–15 port 1).
func (d *PCA6416AFull) ReadAll() (uint16, error) {
	return d.read16(pca6416aRegInput)
}

// WriteAll writes both OUTPUT registers in one two-byte transaction and updates
// the shadow; bit n = pin n.
func (d *PCA6416AFull) WriteAll(value uint16) error {
	d.shadow = value
	return d.write16(pca6416aRegOutput, value)
}

// ConfigurePolarity writes POLARITY0/1: mask bit n = 1 inverts the INPUT bit of
// pin n.
func (d *PCA6416AFull) ConfigurePolarity(mask uint16) error {
	d.polarity = mask
	if err := d.write16(pca6416aRegPolarity, mask); err != nil {
		return err
	}
	v, err := d.read16(pca6416aRegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	return nil
}

// GetDirection returns the shadow CONFIG registers as a 16-bit value (1 = input).
func (d *PCA6416AFull) GetDirection() uint16 {
	return d.direction
}

// SetDirection writes CONFIG0/1 in one two-byte transaction (1 = input, 0 =
// output). OUTPUT is written first so pins turning into outputs never glitch;
// the cached input value is refreshed afterwards to drop the false interrupt an
// output→input change can raise.
func (d *PCA6416AFull) SetDirection(mask uint16) error {
	if err := d.write16(pca6416aRegOutput, d.shadow); err != nil {
		return err
	}
	d.direction = mask
	if err := d.write16(pca6416aRegConfig, mask); err != nil {
		return err
	}
	v, err := d.read16(pca6416aRegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	return nil
}

// ReadRegister reads a raw register 0–7 (debug / escape hatch).
func (d *PCA6416AFull) ReadRegister(reg uint8) (uint8, error) {
	if reg > 7 {
		return 0, errors.New("pca6416a: register must be 0-7")
	}
	return d.readReg(reg)
}

// WriteRegister writes a raw register 2–7; the OUTPUT / POLARITY / CONFIG
// shadows are kept in sync. Writing register 0 or 1 (INPUT, read-only) or any
// other address returns an error.
func (d *PCA6416AFull) WriteRegister(reg, value uint8) error {
	if reg < 2 || reg > 7 {
		return errors.New("pca6416a: register must be 2-7 (INPUT is read-only)")
	}
	shift := 8 * uint16(reg&1)
	keep := ^(uint16(0xFF) << shift)
	bits := uint16(value) << shift
	switch reg >> 1 {
	case 1:
		d.shadow = d.shadow&keep | bits
	case 2:
		d.polarity = d.polarity&keep | bits
	default:
		d.direction = d.direction&keep | bits
	}
	return d.writeReg(reg, value)
}

// Reset writes the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF, both
// ports) and refreshes the input cache. The RESET pin is external and not
// driven.
func (d *PCA6416AFull) Reset() error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	if err := d.write16(pca6416aRegOutput, 0xFFFF); err != nil {
		return err
	}
	if err := d.write16(pca6416aRegPolarity, 0x0000); err != nil {
		return err
	}
	if err := d.write16(pca6416aRegConfig, 0xFFFF); err != nil {
		return err
	}
	d.shadow, d.polarity, d.direction = 0xFFFF, 0x0000, 0xFFFF
	v, err := d.read16(pca6416aRegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	d.level = v
	return nil
}

// PCA6416AFullPin is a GPIO proxy for a single pin obtained via
// (*PCA6416AFull).Pin — adds Watch/Unwatch and satisfies connection.InputPin
// (OnEdge, IsHigh) as well as connection.OutputPin. There is no SetPullUp: the
// chip has no pull-up resistors.
type PCA6416AFullPin struct {
	PCA6416APin
	full *PCA6416AFull
}

// Pin returns a Full pin proxy for pin n (0–15).
func (d *PCA6416AFull) Pin(n uint8) PCA6416AFullPin {
	return PCA6416AFullPin{PCA6416APin: d.PCA6416AMinimal.Pin(n), full: d}
}

// IsHigh reads the actual pin level, satisfying connection.InputPin.
func (p PCA6416AFullPin) IsHigh() (bool, error) {
	return p.Read()
}

// Watch subscribes handler to changes on this pin matching trigger. The chip
// reports changes only; the rising/falling filter is applied in software.
// Delivery starts once OnInterrupt has been called. At most one handler per
// pin; a second Watch replaces the first. Output pins cannot interrupt, so
// Watch on one returns an error.
func (p PCA6416AFullPin) Watch(trigger connection.Trigger, handler func(PCA6416AFullPin)) error {
	if (p.full.direction>>p.n)&1 == 0 {
		return errors.New("pca6416a: cannot watch an output pin")
	}
	p.full.mu.Lock()
	p.full.watches[p.n] = &pca6416aWatch{trigger: trigger, handler: handler}
	p.full.mu.Unlock()
	return nil
}

// Unwatch removes this pin's handler, if any.
func (p PCA6416AFullPin) Unwatch() error {
	p.full.mu.Lock()
	p.full.watches[p.n] = nil
	p.full.mu.Unlock()
	return nil
}

// OnEdge registers handler for edges on this pin, satisfying
// connection.InputPin, and returns a function that removes it.
func (p PCA6416AFullPin) OnEdge(trigger connection.Trigger, handler func()) (unsubscribe func()) {
	if err := p.Watch(trigger, func(PCA6416AFullPin) { handler() }); err != nil {
		return func() {}
	}
	return func() { _ = p.Unwatch() }
}

// OnInterrupt subscribes callback to INT assertions (falling edge): it receives
// the 16-bit mask of input pins whose level changed since the previous INPUT read. Uses
// the connection's InputPin if wired, otherwise a 5 ms polling goroutine.
func (d *PCA6416AFull) OnInterrupt(callback func(changed uint16)) error {
	d.mu.Lock()
	if d.unsubscribe != nil {
		d.unsubscribe()
		d.unsubscribe = nil
	}
	if d.pollPin != nil {
		_ = d.pollPin.Close()
		d.pollPin = nil
	}
	d.callback = callback

	pin := d.conn.IntPin()
	if pin == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.pollPin = poll
		pin = poll
	}
	d.mu.Unlock()

	unsub := pin.OnEdge(connection.Falling, d.handleEdge)
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes interrupt delivery.
func (d *PCA6416AFull) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	d.callback = nil
	poll := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

// PollInterrupt reads both INPUT registers (clearing INT) and returns the 16-bit
// changed-pin mask: the
// XOR of the new and the cached INPUT value, limited to pins configured as
// inputs. The cache is updated.
func (d *PCA6416AFull) PollInterrupt() (uint16, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	now, err := d.read16(pca6416aRegInput)
	if err != nil {
		return 0, err
	}
	changed := (now ^ d.lastInput) & d.direction
	d.lastInput = now
	d.level = now
	return changed, nil
}

// handleEdge is called on each INT edge (real or polled). It computes the
// changed mask, then dispatches the callback and any matching per-pin watches.
func (d *PCA6416AFull) handleEdge() {
	changed, err := d.PollInterrupt()
	if err != nil || changed == 0 {
		return
	}

	d.mu.Lock()
	cb := d.callback
	level := d.level
	type call struct {
		fn  func(PCA6416AFullPin)
		pin PCA6416AFullPin
	}
	var calls []call
	for n := uint8(0); n < 16; n++ {
		w := d.watches[n]
		if w == nil || (changed>>n)&1 == 0 {
			continue
		}
		high := (level>>n)&1 == 1
		fire := w.trigger == connection.Change ||
			(w.trigger == connection.Rising && high) ||
			(w.trigger == connection.Falling && !high)
		if fire {
			calls = append(calls, call{fn: w.handler, pin: d.Pin(n)})
		}
	}
	d.mu.Unlock()

	if cb != nil {
		cb(changed)
	}
	for _, c := range calls {
		c.fn(c.pin)
	}
}

package ioexpander

import (
	"errors"
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCA9539 register addresses.
const (
	// Port-0 register addresses; port 1 is +1. A two-byte transfer at a
	// port-0 register toggles to port 1 (register-pair layout).
	pca9539RegInput    uint8 = 0x00
	pca9539RegOutput   uint8 = 0x02
	pca9539RegPolarity uint8 = 0x04
	pca9539RegConfig   uint8 = 0x06
)

// Pin modes accepted by (PCA9539Pin).Mode.
const (
	// PCA9539Input configures the pin as a high-impedance input.
	PCA9539Input PinMode = iota
	// PCA9539Output configures the pin as a push-pull output.
	PCA9539Output
	// PCA9539InputPullup is rejected: the chip has no pull-up resistors.
	PCA9539InputPullup
)

// ErrPCA9539NoPullup is returned for any request to enable a pull-up: the
// PCA9539 has no pull-up resistors.
var ErrPCA9539NoPullup = errors.New("pca9539: no pull-up resistors")

// PCA9539Minimal is the PCA9539 (NXP) 16-bit I/O expander driver — minimal
// interface. I²C / SMBus (no PEC).
//
// All sixteen pins (0–7 = P00–P07, 8–15 = P10–P17) are bidirectional.
// Direction is controlled per pin via CONFIG (1 = input, 0 = output); 16-bit
// shadows of OUTPUT and CONFIG make a single-pin change one register write. The
// chip has no internal pull-ups. The registers form pairs: a two-byte transfer
// starting at a port-0 register covers both ports.
type PCA9539Minimal struct {
	conn      connection.RegisterConnection
	shadow    uint16 // shadow of OUTPUT0/1
	direction uint16 // shadow of CONFIG0/1 (1 = input)
	polarity  uint16 // shadow of POLARITY0/1
	lastInput uint16 // last INPUT0/1 value read (interrupt reference)
}

// NewPCA9539Minimal creates a PCA9539 driver and runs the initialisation
// sequence: OUTPUT ← 0xFF, POLARITY ← 0x00, CONFIG ← 0xFF on both ports (all
// inputs), then INPUT0/1 is read to initialise the cached input value.
//
// conn must be a configured I²C or SMBus (no PEC) connection bound to the
// device's 7-bit address (0x74 with A1 = A0 = GND … 0x77 with A1 = A0 = VCC); addr
// is informational.
func NewPCA9539Minimal(conn connection.RegisterConnection, addr uint8) (*PCA9539Minimal, error) {
	_ = addr
	d := &PCA9539Minimal{conn: conn, shadow: 0xFFFF, direction: 0xFFFF}
	if err := d.write16(pca9539RegOutput, 0xFFFF); err != nil {
		return nil, err
	}
	if err := d.write16(pca9539RegPolarity, 0x0000); err != nil {
		return nil, err
	}
	if err := d.write16(pca9539RegConfig, 0xFFFF); err != nil {
		return nil, err
	}
	v, err := d.read16(pca9539RegInput)
	if err != nil {
		return nil, err
	}
	d.lastInput = v
	return d, nil
}

func (d *PCA9539Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *PCA9539Minimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

// read16 reads both ports of a register pair in one two-byte transfer starting
// at the port-0 register (port 0 first, then port 1).
func (d *PCA9539Minimal) read16(base uint8) (uint16, error) {
	buf, err := d.conn.ReadReg(uint32(base), 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0]) | uint16(buf[1])<<8, nil
}

func (d *PCA9539Minimal) write16(base uint8, value uint16) error {
	return d.conn.WriteReg(uint32(base), []byte{byte(value), byte(value >> 8)})
}

// ReadPort reads one 8-pin port via its INPUT register (actual pin levels).
// port is 0 (P00–P07) or 1 (P10–P17); only bit 0 is used.
func (d *PCA9539Minimal) ReadPort(port uint8) (uint8, error) {
	return d.readReg(pca9539RegInput + (port & 1))
}

// WritePort writes all 8 output bits of one port via OUTPUTx and updates the
// shadow. Bits of input pins are stored but have no effect until the pin
// becomes an output. port is 0 or 1; only bit 0 is used.
func (d *PCA9539Minimal) WritePort(port uint8, mask uint8) error {
	port &= 1
	shift := 8 * uint16(port)
	d.shadow = d.shadow&^(0xFF<<shift) | uint16(mask)<<shift
	return d.writeReg(pca9539RegOutput+port, mask)
}

// Pin returns a Pin proxy for pin n (0–15).
func (d *PCA9539Minimal) Pin(n uint8) PCA9539Pin {
	return PCA9539Pin{chip: d, n: n & 15}
}

func (d *PCA9539Minimal) setPin(n uint8, high bool) error {
	if high {
		d.shadow |= 1 << n
	} else {
		d.shadow &^= 1 << n
	}
	return d.writeReg(pca9539RegOutput+n>>3, uint8(d.shadow>>(8*(n>>3))))
}

// setDirection writes the pin's OUTPUT bit before clearing its CONFIG bit so the
// pin never glitches to a stale level.
func (d *PCA9539Minimal) setDirection(n uint8, input bool) error {
	port := n >> 3
	if input {
		d.direction |= 1 << n
	} else {
		if err := d.writeReg(pca9539RegOutput+port, uint8(d.shadow>>(8*port))); err != nil {
			return err
		}
		d.direction &^= 1 << n
	}
	return d.writeReg(pca9539RegConfig+port, uint8(d.direction>>(8*port)))
}

// PCA9539Pin is a GPIO proxy for a single pin. It satisfies
// connection.OutputPin (Set), so an output pin can be used directly as the EN
// pin of another chip's connection.
//
// Obtained via (*PCA9539Minimal).Pin.
type PCA9539Pin struct {
	chip *PCA9539Minimal
	n    uint8
}

// Mode sets the pin direction. PCA9539InputPullup is rejected with
// ErrPCA9539NoPullup and nothing is written.
func (p PCA9539Pin) Mode(m PinMode) error {
	if m == PCA9539InputPullup {
		return ErrPCA9539NoPullup
	}
	return p.chip.setDirection(p.n, m != PCA9539Output)
}

// Write sets the output latch to high (true) or low (false).
func (p PCA9539Pin) Write(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Set is Write, satisfying connection.OutputPin.
func (p PCA9539Pin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Read reads the actual logic level at the pin via the INPUT register.
func (p PCA9539Pin) Read() (bool, error) {
	v, err := p.chip.readReg(pca9539RegInput + p.n>>3)
	if err != nil {
		return false, err
	}
	return (v>>(p.n&7))&1 == 1, nil
}

// Toggle inverts the output latch bit for this pin.
func (p PCA9539Pin) Toggle() error {
	return p.chip.setPin(p.n, (p.chip.shadow>>p.n)&1 == 0)
}

type pca9539Watch struct {
	trigger connection.Trigger
	handler func(PCA9539FullPin)
}

// PCA9539Full is the PCA9539 driver — full interface. Extends PCA9539Minimal
// with polarity inversion, whole-device direction, change-based interrupts and raw
// register access.
type PCA9539Full struct {
	*PCA9539Minimal

	mu          sync.Mutex
	level       uint16
	callback    func(uint16)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	watches     [16]*pca9539Watch
}

// NewPCA9539Full creates a PCA9539 full driver with the same initialisation as
// NewPCA9539Minimal.
func NewPCA9539Full(conn connection.RegisterConnection, addr uint8) (*PCA9539Full, error) {
	m, err := NewPCA9539Minimal(conn, addr)
	if err != nil {
		return nil, err
	}
	return &PCA9539Full{PCA9539Minimal: m, level: m.lastInput}, nil
}

// ReadAll reads both INPUT registers in one two-byte transaction; bit n = pin n
// (bits 0–7 port 0, 8–15 port 1).
func (d *PCA9539Full) ReadAll() (uint16, error) {
	return d.read16(pca9539RegInput)
}

// WriteAll writes both OUTPUT registers in one two-byte transaction and updates
// the shadow; bit n = pin n.
func (d *PCA9539Full) WriteAll(value uint16) error {
	d.shadow = value
	return d.write16(pca9539RegOutput, value)
}

// ConfigurePolarity writes POLARITY0/1: mask bit n = 1 inverts the INPUT bit of
// pin n.
func (d *PCA9539Full) ConfigurePolarity(mask uint16) error {
	d.polarity = mask
	if err := d.write16(pca9539RegPolarity, mask); err != nil {
		return err
	}
	v, err := d.read16(pca9539RegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	return nil
}

// GetDirection returns the shadow CONFIG registers as a 16-bit value (1 = input).
func (d *PCA9539Full) GetDirection() uint16 {
	return d.direction
}

// SetDirection writes CONFIG0/1 in one two-byte transaction (1 = input, 0 =
// output). OUTPUT is written first so pins turning into outputs never glitch;
// the cached input value is refreshed afterwards to drop the false interrupt an
// output→input change can raise.
func (d *PCA9539Full) SetDirection(mask uint16) error {
	if err := d.write16(pca9539RegOutput, d.shadow); err != nil {
		return err
	}
	d.direction = mask
	if err := d.write16(pca9539RegConfig, mask); err != nil {
		return err
	}
	v, err := d.read16(pca9539RegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	return nil
}

// ReadRegister reads a raw register 0–7 (debug / escape hatch).
func (d *PCA9539Full) ReadRegister(reg uint8) (uint8, error) {
	if reg > 7 {
		return 0, errors.New("pca9539: register must be 0-7")
	}
	return d.readReg(reg)
}

// WriteRegister writes a raw register 2–7; the OUTPUT / POLARITY / CONFIG
// shadows are kept in sync. Writing register 0 or 1 (INPUT, read-only) or any
// other address returns an error.
func (d *PCA9539Full) WriteRegister(reg, value uint8) error {
	if reg < 2 || reg > 7 {
		return errors.New("pca9539: register must be 2-7 (INPUT is read-only)")
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
func (d *PCA9539Full) Reset() error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	if err := d.write16(pca9539RegOutput, 0xFFFF); err != nil {
		return err
	}
	if err := d.write16(pca9539RegPolarity, 0x0000); err != nil {
		return err
	}
	if err := d.write16(pca9539RegConfig, 0xFFFF); err != nil {
		return err
	}
	d.shadow, d.polarity, d.direction = 0xFFFF, 0x0000, 0xFFFF
	v, err := d.read16(pca9539RegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	d.level = v
	return nil
}

// PCA9539FullPin is a GPIO proxy for a single pin obtained via
// (*PCA9539Full).Pin — adds Watch/Unwatch and satisfies connection.InputPin
// (OnEdge, IsHigh) as well as connection.OutputPin. There is no SetPullUp: the
// chip has no pull-up resistors.
type PCA9539FullPin struct {
	PCA9539Pin
	full *PCA9539Full
}

// Pin returns a Full pin proxy for pin n (0–15).
func (d *PCA9539Full) Pin(n uint8) PCA9539FullPin {
	return PCA9539FullPin{PCA9539Pin: d.PCA9539Minimal.Pin(n), full: d}
}

// IsHigh reads the actual pin level, satisfying connection.InputPin.
func (p PCA9539FullPin) IsHigh() (bool, error) {
	return p.Read()
}

// Watch subscribes handler to changes on this pin matching trigger. The chip
// reports changes only; the rising/falling filter is applied in software.
// Delivery starts once OnInterrupt has been called. At most one handler per
// pin; a second Watch replaces the first. Output pins cannot interrupt, so
// Watch on one returns an error.
func (p PCA9539FullPin) Watch(trigger connection.Trigger, handler func(PCA9539FullPin)) error {
	if (p.full.direction>>p.n)&1 == 0 {
		return errors.New("pca9539: cannot watch an output pin")
	}
	p.full.mu.Lock()
	p.full.watches[p.n] = &pca9539Watch{trigger: trigger, handler: handler}
	p.full.mu.Unlock()
	return nil
}

// Unwatch removes this pin's handler, if any.
func (p PCA9539FullPin) Unwatch() error {
	p.full.mu.Lock()
	p.full.watches[p.n] = nil
	p.full.mu.Unlock()
	return nil
}

// OnEdge registers handler for edges on this pin, satisfying
// connection.InputPin, and returns a function that removes it.
func (p PCA9539FullPin) OnEdge(trigger connection.Trigger, handler func()) (unsubscribe func()) {
	if err := p.Watch(trigger, func(PCA9539FullPin) { handler() }); err != nil {
		return func() {}
	}
	return func() { _ = p.Unwatch() }
}

// OnInterrupt subscribes callback to INT assertions (falling edge): it receives
// the 16-bit mask of input pins whose level changed since the previous INPUT read. Uses
// the connection's InputPin if wired, otherwise a 5 ms polling goroutine.
func (d *PCA9539Full) OnInterrupt(callback func(changed uint16)) error {
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
func (d *PCA9539Full) OffInterrupt() error {
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
func (d *PCA9539Full) PollInterrupt() (uint16, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	now, err := d.read16(pca9539RegInput)
	if err != nil {
		return 0, err
	}
	if d.callback != nil {
		// Errata (datasheet 8.2.4.1): keep the command byte != 0x00 after an INPUT read.
		if err := d.writeReg(pca9539RegOutput, uint8(d.shadow)); err != nil {
			return 0, err
		}
	}
	changed := (now ^ d.lastInput) & d.direction
	d.lastInput = now
	d.level = now
	return changed, nil
}

// handleEdge is called on each INT edge (real or polled). It computes the
// changed mask, then dispatches the callback and any matching per-pin watches.
func (d *PCA9539Full) handleEdge() {
	changed, err := d.PollInterrupt()
	if err != nil || changed == 0 {
		return
	}

	d.mu.Lock()
	cb := d.callback
	level := d.level
	type call struct {
		fn  func(PCA9539FullPin)
		pin PCA9539FullPin
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

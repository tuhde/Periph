package ioexpander

import (
	"errors"
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCA6408A register addresses.
const (
	pca6408aRegInput    uint8 = 0x00
	pca6408aRegOutput   uint8 = 0x01
	pca6408aRegPolarity uint8 = 0x02
	pca6408aRegConfig   uint8 = 0x03
)

// Pin modes accepted by (PCA6408APin).Mode.
const (
	// PCA6408AInput configures the pin as a high-impedance input.
	PCA6408AInput PinMode = iota
	// PCA6408AOutput configures the pin as a push-pull output.
	PCA6408AOutput
	// PCA6408AInputPullup is rejected: the chip has no pull-up resistors.
	PCA6408AInputPullup
)

// ErrPCA6408ANoPullup is returned for any request to enable a pull-up: the
// PCA6408A has no pull-up resistors.
var ErrPCA6408ANoPullup = errors.New("pca6408a: no pull-up resistors")

// PCA6408AMinimal is the PCA6408A (NXP) 8-bit I/O expander driver — minimal
// interface. I²C / SMBus (no PEC).
//
// All eight pins (P0–P7) are bidirectional. Direction is controlled per pin via
// CONFIG (1 = input, 0 = output); shadows of OUTPUT and CONFIG make a
// single-pin change one register write. The chip has no internal pull-ups and
// no register auto-increment: every register is accessed one byte at a time.
type PCA6408AMinimal struct {
	conn      connection.RegisterConnection
	shadow    uint8 // shadow of OUTPUT
	direction uint8 // shadow of CONFIG (1 = input)
	polarity  uint8 // shadow of POLARITY
	lastInput uint8 // last INPUT value read (interrupt reference)
}

// NewPCA6408AMinimal creates a PCA6408A driver and runs the initialisation
// sequence: OUTPUT ← 0xFF, POLARITY ← 0x00, CONFIG ← 0xFF (all inputs), then
// INPUT is read to initialise the cached input value.
//
// conn must be a configured I²C or SMBus (no PEC) connection bound to the
// device's 7-bit address (0x20 with ADDR = VSS, 0x21 with ADDR = VDD(P)); addr
// is informational.
func NewPCA6408AMinimal(conn connection.RegisterConnection, addr uint8) (*PCA6408AMinimal, error) {
	_ = addr
	d := &PCA6408AMinimal{conn: conn, shadow: 0xFF, direction: 0xFF}
	if err := d.writeReg(pca6408aRegOutput, 0xFF); err != nil {
		return nil, err
	}
	if err := d.writeReg(pca6408aRegPolarity, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(pca6408aRegConfig, 0xFF); err != nil {
		return nil, err
	}
	v, err := d.readReg(pca6408aRegInput)
	if err != nil {
		return nil, err
	}
	d.lastInput = v
	return d, nil
}

func (d *PCA6408AMinimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *PCA6408AMinimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

// ReadPort reads all 8 pins via the INPUT register (actual pin levels). port is
// ignored; the chip has a single port.
func (d *PCA6408AMinimal) ReadPort(port uint8) (uint8, error) {
	_ = port
	return d.readReg(pca6408aRegInput)
}

// WritePort writes all 8 output bits via OUTPUT and updates the shadow. Bits of
// input pins are stored but have no effect until the pin becomes an output.
// port is ignored; the chip has a single port.
func (d *PCA6408AMinimal) WritePort(port uint8, mask uint8) error {
	_ = port
	d.shadow = mask
	return d.writeReg(pca6408aRegOutput, mask)
}

// Pin returns a Pin proxy for pin n (0–7).
func (d *PCA6408AMinimal) Pin(n uint8) PCA6408APin {
	return PCA6408APin{chip: d, n: n & 7}
}

func (d *PCA6408AMinimal) setPin(n uint8, high bool) error {
	if high {
		d.shadow |= 1 << n
	} else {
		d.shadow &^= 1 << n
	}
	return d.writeReg(pca6408aRegOutput, d.shadow)
}

// setDirection writes the pin's OUTPUT bit before clearing its CONFIG bit so the
// pin never glitches to a stale level.
func (d *PCA6408AMinimal) setDirection(n uint8, input bool) error {
	if input {
		d.direction |= 1 << n
	} else {
		if err := d.writeReg(pca6408aRegOutput, d.shadow); err != nil {
			return err
		}
		d.direction &^= 1 << n
	}
	return d.writeReg(pca6408aRegConfig, d.direction)
}

// PCA6408APin is a GPIO proxy for a single pin. It satisfies
// connection.OutputPin (Set), so an output pin can be used directly as the EN
// pin of another chip's connection.
//
// Obtained via (*PCA6408AMinimal).Pin.
type PCA6408APin struct {
	chip *PCA6408AMinimal
	n    uint8
}

// Mode sets the pin direction. PCA6408AInputPullup is rejected with
// ErrPCA6408ANoPullup and nothing is written.
func (p PCA6408APin) Mode(m PinMode) error {
	if m == PCA6408AInputPullup {
		return ErrPCA6408ANoPullup
	}
	return p.chip.setDirection(p.n, m != PCA6408AOutput)
}

// Write sets the output latch to high (true) or low (false).
func (p PCA6408APin) Write(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Set is Write, satisfying connection.OutputPin.
func (p PCA6408APin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Read reads the actual logic level at the pin via the INPUT register.
func (p PCA6408APin) Read() (bool, error) {
	v, err := p.chip.readReg(pca6408aRegInput)
	if err != nil {
		return false, err
	}
	return (v>>p.n)&1 == 1, nil
}

// Toggle inverts the output latch bit for this pin.
func (p PCA6408APin) Toggle() error {
	return p.chip.setPin(p.n, (p.chip.shadow>>p.n)&1 == 0)
}

type pca6408aWatch struct {
	trigger connection.Trigger
	handler func(PCA6408AFullPin)
}

// PCA6408AFull is the PCA6408A driver — full interface. Extends PCA6408AMinimal
// with polarity inversion, whole-port direction, change-based interrupts and raw
// register access.
type PCA6408AFull struct {
	*PCA6408AMinimal

	mu          sync.Mutex
	level       uint8
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	watches     [8]*pca6408aWatch
}

// NewPCA6408AFull creates a PCA6408A full driver with the same initialisation as
// NewPCA6408AMinimal.
func NewPCA6408AFull(conn connection.RegisterConnection, addr uint8) (*PCA6408AFull, error) {
	m, err := NewPCA6408AMinimal(conn, addr)
	if err != nil {
		return nil, err
	}
	return &PCA6408AFull{PCA6408AMinimal: m, level: m.lastInput}, nil
}

// ConfigurePolarity writes POLARITY: mask bit n = 1 inverts the INPUT bit of Pn.
func (d *PCA6408AFull) ConfigurePolarity(mask uint8) error {
	d.polarity = mask
	if err := d.writeReg(pca6408aRegPolarity, mask); err != nil {
		return err
	}
	v, err := d.readReg(pca6408aRegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	return nil
}

// GetDirection returns the shadow CONFIG register (1 = input).
func (d *PCA6408AFull) GetDirection() uint8 {
	return d.direction
}

// SetDirection writes CONFIG in one transaction (1 = input, 0 = output). OUTPUT
// is written first so pins turning into outputs never glitch; the cached input
// value is refreshed afterwards to drop the false interrupt an output→input
// change can raise.
func (d *PCA6408AFull) SetDirection(mask uint8) error {
	if err := d.writeReg(pca6408aRegOutput, d.shadow); err != nil {
		return err
	}
	d.direction = mask
	if err := d.writeReg(pca6408aRegConfig, mask); err != nil {
		return err
	}
	v, err := d.readReg(pca6408aRegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	return nil
}

// ReadRegister reads a raw register 0–3 (debug / escape hatch).
func (d *PCA6408AFull) ReadRegister(reg uint8) (uint8, error) {
	if reg > 3 {
		return 0, errors.New("pca6408a: register must be 0-3")
	}
	return d.readReg(reg)
}

// WriteRegister writes a raw register 1–3; the OUTPUT / POLARITY / CONFIG
// shadows are kept in sync. Writing register 0 (INPUT, read-only) or any other
// address returns an error.
func (d *PCA6408AFull) WriteRegister(reg, value uint8) error {
	switch reg {
	case pca6408aRegOutput:
		d.shadow = value
	case pca6408aRegPolarity:
		d.polarity = value
	case pca6408aRegConfig:
		d.direction = value
	default:
		return errors.New("pca6408a: register must be 1-3 (INPUT is read-only)")
	}
	return d.writeReg(reg, value)
}

// Reset writes the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF) and
// refreshes the input cache. The RESET pin is external and not driven.
func (d *PCA6408AFull) Reset() error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	if err := d.writeReg(pca6408aRegOutput, 0xFF); err != nil {
		return err
	}
	if err := d.writeReg(pca6408aRegPolarity, 0x00); err != nil {
		return err
	}
	if err := d.writeReg(pca6408aRegConfig, 0xFF); err != nil {
		return err
	}
	d.shadow, d.polarity, d.direction = 0xFF, 0x00, 0xFF
	v, err := d.readReg(pca6408aRegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	d.level = v
	return nil
}

// PCA6408AFullPin is a GPIO proxy for a single pin obtained via
// (*PCA6408AFull).Pin — adds Watch/Unwatch and satisfies connection.InputPin
// (OnEdge, IsHigh) as well as connection.OutputPin. There is no SetPullUp: the
// chip has no pull-up resistors.
type PCA6408AFullPin struct {
	PCA6408APin
	full *PCA6408AFull
}

// Pin returns a Full pin proxy for pin n (0–7).
func (d *PCA6408AFull) Pin(n uint8) PCA6408AFullPin {
	return PCA6408AFullPin{PCA6408APin: d.PCA6408AMinimal.Pin(n), full: d}
}

// IsHigh reads the actual pin level, satisfying connection.InputPin.
func (p PCA6408AFullPin) IsHigh() (bool, error) {
	return p.Read()
}

// Watch subscribes handler to changes on this pin matching trigger. The chip
// reports changes only; the rising/falling filter is applied in software.
// Delivery starts once OnInterrupt has been called. At most one handler per
// pin; a second Watch replaces the first. Output pins cannot interrupt, so
// Watch on one returns an error.
func (p PCA6408AFullPin) Watch(trigger connection.Trigger, handler func(PCA6408AFullPin)) error {
	if (p.full.direction>>p.n)&1 == 0 {
		return errors.New("pca6408a: cannot watch an output pin")
	}
	p.full.mu.Lock()
	p.full.watches[p.n] = &pca6408aWatch{trigger: trigger, handler: handler}
	p.full.mu.Unlock()
	return nil
}

// Unwatch removes this pin's handler, if any.
func (p PCA6408AFullPin) Unwatch() error {
	p.full.mu.Lock()
	p.full.watches[p.n] = nil
	p.full.mu.Unlock()
	return nil
}

// OnEdge registers handler for edges on this pin, satisfying
// connection.InputPin, and returns a function that removes it.
func (p PCA6408AFullPin) OnEdge(trigger connection.Trigger, handler func()) (unsubscribe func()) {
	if err := p.Watch(trigger, func(PCA6408AFullPin) { handler() }); err != nil {
		return func() {}
	}
	return func() { _ = p.Unwatch() }
}

// OnInterrupt subscribes callback to INT assertions (falling edge): it receives
// the mask of input pins whose level changed since the previous INPUT read. Uses
// the connection's InputPin if wired, otherwise a 5 ms polling goroutine.
func (d *PCA6408AFull) OnInterrupt(callback func(changed uint8)) error {
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
func (d *PCA6408AFull) OffInterrupt() error {
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

// PollInterrupt reads INPUT (clearing INT) and returns the changed-pin mask: the
// XOR of the new and the cached INPUT value, limited to pins configured as
// inputs. The cache is updated.
func (d *PCA6408AFull) PollInterrupt() (uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	now, err := d.readReg(pca6408aRegInput)
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
func (d *PCA6408AFull) handleEdge() {
	changed, err := d.PollInterrupt()
	if err != nil || changed == 0 {
		return
	}

	d.mu.Lock()
	cb := d.callback
	level := d.level
	type call struct {
		fn  func(PCA6408AFullPin)
		pin PCA6408AFullPin
	}
	var calls []call
	for n := uint8(0); n < 8; n++ {
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

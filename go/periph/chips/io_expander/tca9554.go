package ioexpander

import (
	"errors"
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// TCA9554 register addresses.
const (
	tca9554RegInput    uint8 = 0x00
	tca9554RegOutput   uint8 = 0x01
	tca9554RegPolarity uint8 = 0x02
	tca9554RegConfig   uint8 = 0x03
)

// Pin modes accepted by (TCA9554Pin).Mode.
const (
	// TCA9554Input configures the pin as a high-impedance input.
	TCA9554Input PinMode = iota
	// TCA9554Output configures the pin as a push-pull output.
	TCA9554Output
	// TCA9554InputPullup is accepted and behaves like TCA9554Input (the pull-up is fixed).
	TCA9554InputPullup
)

// TCA9554Minimal is the TCA9554 (TI) 8-bit I/O expander driver — minimal
// interface. I²C / SMBus (no PEC).
//
// All eight pins (P0–P7) are bidirectional. Direction is controlled per pin via
// CONFIG (1 = input, 0 = output); shadows of OUTPUT and CONFIG make a
// single-pin change one register write. Every input has a fixed 100 kΩ pull-up to VCC and
// no register auto-increment: every register is accessed one byte at a time.
type TCA9554Minimal struct {
	conn      connection.RegisterConnection
	shadow    uint8 // shadow of OUTPUT
	direction uint8 // shadow of CONFIG (1 = input)
	polarity  uint8 // shadow of POLARITY
	lastInput uint8 // last INPUT value read (interrupt reference)
}

// NewTCA9554Minimal creates a TCA9554 driver and runs the initialisation
// sequence: OUTPUT ← 0xFF, POLARITY ← 0x00, CONFIG ← 0xFF (all inputs), then
// INPUT is read to initialise the cached input value.
//
// conn must be a configured I²C or SMBus (no PEC) connection bound to the
// device's 7-bit address (0x20–0x27 from A2..A0; TCA9554A: 0x38–0x3F); addr
// is informational.
func NewTCA9554Minimal(conn connection.RegisterConnection, addr uint8) (*TCA9554Minimal, error) {
	_ = addr
	d := &TCA9554Minimal{conn: conn, shadow: 0xFF, direction: 0xFF}
	if err := d.writeReg(tca9554RegOutput, 0xFF); err != nil {
		return nil, err
	}
	if err := d.writeReg(tca9554RegPolarity, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(tca9554RegConfig, 0xFF); err != nil {
		return nil, err
	}
	v, err := d.readReg(tca9554RegInput)
	if err != nil {
		return nil, err
	}
	d.lastInput = v
	return d, nil
}

func (d *TCA9554Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *TCA9554Minimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

// ReadPort reads all 8 pins via the INPUT register (actual pin levels). port is
// ignored; the chip has a single port.
func (d *TCA9554Minimal) ReadPort(port uint8) (uint8, error) {
	_ = port
	return d.readReg(tca9554RegInput)
}

// WritePort writes all 8 output bits via OUTPUT and updates the shadow. Bits of
// input pins are stored but have no effect until the pin becomes an output.
// port is ignored; the chip has a single port.
func (d *TCA9554Minimal) WritePort(port uint8, mask uint8) error {
	_ = port
	d.shadow = mask
	return d.writeReg(tca9554RegOutput, mask)
}

// Pin returns a Pin proxy for pin n (0–7).
func (d *TCA9554Minimal) Pin(n uint8) TCA9554Pin {
	return TCA9554Pin{chip: d, n: n & 7}
}

func (d *TCA9554Minimal) setPin(n uint8, high bool) error {
	if high {
		d.shadow |= 1 << n
	} else {
		d.shadow &^= 1 << n
	}
	return d.writeReg(tca9554RegOutput, d.shadow)
}

// setDirection writes the pin's OUTPUT bit before clearing its CONFIG bit so the
// pin never glitches to a stale level.
func (d *TCA9554Minimal) setDirection(n uint8, input bool) error {
	if input {
		d.direction |= 1 << n
	} else {
		if err := d.writeReg(tca9554RegOutput, d.shadow); err != nil {
			return err
		}
		d.direction &^= 1 << n
	}
	return d.writeReg(tca9554RegConfig, d.direction)
}

// TCA9554Pin is a GPIO proxy for a single pin. It satisfies
// connection.OutputPin (Set), so an output pin can be used directly as the EN
// pin of another chip's connection.
//
// Obtained via (*TCA9554Minimal).Pin.
type TCA9554Pin struct {
	chip *TCA9554Minimal
	n    uint8
}

// Mode sets the pin direction. TCA9554InputPullup is accepted as a plain
// input: the 100 kΩ pull-up is fixed and always on.
func (p TCA9554Pin) Mode(m PinMode) error {
	return p.chip.setDirection(p.n, m != TCA9554Output)
}

// Write sets the output latch to high (true) or low (false).
func (p TCA9554Pin) Write(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Set is Write, satisfying connection.OutputPin.
func (p TCA9554Pin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Read reads the actual logic level at the pin via the INPUT register.
func (p TCA9554Pin) Read() (bool, error) {
	v, err := p.chip.readReg(tca9554RegInput)
	if err != nil {
		return false, err
	}
	return (v>>p.n)&1 == 1, nil
}

// Toggle inverts the output latch bit for this pin.
func (p TCA9554Pin) Toggle() error {
	return p.chip.setPin(p.n, (p.chip.shadow>>p.n)&1 == 0)
}

type tca9554Watch struct {
	trigger connection.Trigger
	handler func(TCA9554FullPin)
}

// TCA9554Full is the TCA9554 driver — full interface. Extends TCA9554Minimal
// with polarity inversion, whole-port direction, change-based interrupts and raw
// register access.
type TCA9554Full struct {
	*TCA9554Minimal

	mu          sync.Mutex
	level       uint8
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	watches     [8]*tca9554Watch
}

// NewTCA9554Full creates a TCA9554 full driver with the same initialisation as
// NewTCA9554Minimal.
func NewTCA9554Full(conn connection.RegisterConnection, addr uint8) (*TCA9554Full, error) {
	m, err := NewTCA9554Minimal(conn, addr)
	if err != nil {
		return nil, err
	}
	return &TCA9554Full{TCA9554Minimal: m, level: m.lastInput}, nil
}

// ConfigurePolarity writes POLARITY: mask bit n = 1 inverts the INPUT bit of Pn.
func (d *TCA9554Full) ConfigurePolarity(mask uint8) error {
	d.polarity = mask
	if err := d.writeReg(tca9554RegPolarity, mask); err != nil {
		return err
	}
	v, err := d.readReg(tca9554RegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	return nil
}

// GetDirection returns the shadow CONFIG register (1 = input).
func (d *TCA9554Full) GetDirection() uint8 {
	return d.direction
}

// SetDirection writes CONFIG in one transaction (1 = input, 0 = output). OUTPUT
// is written first so pins turning into outputs never glitch; the cached input
// value is refreshed afterwards to drop the false interrupt an output→input
// change can raise.
func (d *TCA9554Full) SetDirection(mask uint8) error {
	if err := d.writeReg(tca9554RegOutput, d.shadow); err != nil {
		return err
	}
	d.direction = mask
	if err := d.writeReg(tca9554RegConfig, mask); err != nil {
		return err
	}
	v, err := d.readReg(tca9554RegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	return nil
}

// ReadRegister reads a raw register 0–3 (debug / escape hatch).
func (d *TCA9554Full) ReadRegister(reg uint8) (uint8, error) {
	if reg > 3 {
		return 0, errors.New("tca9554: register must be 0-3")
	}
	return d.readReg(reg)
}

// WriteRegister writes a raw register 1–3; the OUTPUT / POLARITY / CONFIG
// shadows are kept in sync. Writing register 0 (INPUT, read-only) or any other
// address returns an error.
func (d *TCA9554Full) WriteRegister(reg, value uint8) error {
	switch reg {
	case tca9554RegOutput:
		d.shadow = value
	case tca9554RegPolarity:
		d.polarity = value
	case tca9554RegConfig:
		d.direction = value
	default:
		return errors.New("tca9554: register must be 1-3 (INPUT is read-only)")
	}
	return d.writeReg(reg, value)
}

// Reset writes the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF) and
// refreshes the input cache. There is no RESET pin; only a VCC power cycle resets the chip.
func (d *TCA9554Full) Reset() error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	if err := d.writeReg(tca9554RegOutput, 0xFF); err != nil {
		return err
	}
	if err := d.writeReg(tca9554RegPolarity, 0x00); err != nil {
		return err
	}
	if err := d.writeReg(tca9554RegConfig, 0xFF); err != nil {
		return err
	}
	d.shadow, d.polarity, d.direction = 0xFF, 0x00, 0xFF
	v, err := d.readReg(tca9554RegInput)
	if err != nil {
		return err
	}
	d.lastInput = v
	d.level = v
	return nil
}

// TCA9554FullPin is a GPIO proxy for a single pin obtained via
// (*TCA9554Full).Pin — adds Watch/Unwatch and satisfies connection.InputPin
// (OnEdge, IsHigh) as well as connection.OutputPin. There is no SetPullUp: the
// pull-up is fixed and always on.
type TCA9554FullPin struct {
	TCA9554Pin
	full *TCA9554Full
}

// Pin returns a Full pin proxy for pin n (0–7).
func (d *TCA9554Full) Pin(n uint8) TCA9554FullPin {
	return TCA9554FullPin{TCA9554Pin: d.TCA9554Minimal.Pin(n), full: d}
}

// IsHigh reads the actual pin level, satisfying connection.InputPin.
func (p TCA9554FullPin) IsHigh() (bool, error) {
	return p.Read()
}

// Watch subscribes handler to changes on this pin matching trigger. The chip
// reports changes only; the rising/falling filter is applied in software.
// Delivery starts once OnInterrupt has been called. At most one handler per
// pin; a second Watch replaces the first. Output pins cannot interrupt, so
// Watch on one returns an error.
func (p TCA9554FullPin) Watch(trigger connection.Trigger, handler func(TCA9554FullPin)) error {
	if (p.full.direction>>p.n)&1 == 0 {
		return errors.New("tca9554: cannot watch an output pin")
	}
	p.full.mu.Lock()
	p.full.watches[p.n] = &tca9554Watch{trigger: trigger, handler: handler}
	p.full.mu.Unlock()
	return nil
}

// Unwatch removes this pin's handler, if any.
func (p TCA9554FullPin) Unwatch() error {
	p.full.mu.Lock()
	p.full.watches[p.n] = nil
	p.full.mu.Unlock()
	return nil
}

// OnEdge registers handler for edges on this pin, satisfying
// connection.InputPin, and returns a function that removes it.
func (p TCA9554FullPin) OnEdge(trigger connection.Trigger, handler func()) (unsubscribe func()) {
	if err := p.Watch(trigger, func(TCA9554FullPin) { handler() }); err != nil {
		return func() {}
	}
	return func() { _ = p.Unwatch() }
}

// OnInterrupt subscribes callback to INT assertions (falling edge): it receives
// the mask of input pins whose level changed since the previous INPUT read. Uses
// the connection's InputPin if wired, otherwise a 5 ms polling goroutine.
func (d *TCA9554Full) OnInterrupt(callback func(changed uint8)) error {
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
func (d *TCA9554Full) OffInterrupt() error {
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
func (d *TCA9554Full) PollInterrupt() (uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	now, err := d.readReg(tca9554RegInput)
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
func (d *TCA9554Full) handleEdge() {
	changed, err := d.PollInterrupt()
	if err != nil || changed == 0 {
		return
	}

	d.mu.Lock()
	cb := d.callback
	level := d.level
	type call struct {
		fn  func(TCA9554FullPin)
		pin TCA9554FullPin
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

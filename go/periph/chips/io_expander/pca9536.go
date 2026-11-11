package ioexpander

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCA9536 register addresses.
const (
	pca9536RegInput    uint8 = 0x00
	pca9536RegOutput   uint8 = 0x01
	pca9536RegPolarity uint8 = 0x02
	pca9536RegConfig   uint8 = 0x03

	// pca9536Mask selects the used bits 3:0; bits 7:4 are unused.
	pca9536Mask uint8 = 0x0F
)

// PCA9536I2CAddress is the fixed 7-bit I²C address.
const PCA9536I2CAddress uint8 = 0x41

// Pin modes accepted by (PCA9536Pin).Mode.
const (
	// PCA9536Input configures the pin as a high-impedance input.
	PCA9536Input PinMode = iota
	// PCA9536Output configures the pin as a push-pull output.
	PCA9536Output
	// PCA9536InputPullup is rejected: the weak pull-up is fixed in hardware.
	PCA9536InputPullup
)

// ErrPCA9536NoPullup is returned for any request to enable a pull-up: the
// PCA9536's weak pull-up is always on and cannot be switched.
var ErrPCA9536NoPullup = errors.New("pca9536: pull-up is fixed in hardware")

// PCA9536Minimal is the PCA9536 (TI) 4-bit I/O expander driver — minimal
// interface. I²C / SMBus (no PEC).
//
// All four pins (P0–P3) are bidirectional. Direction is controlled per pin via
// CONFIG (1 = input, 0 = output); shadows of OUTPUT and CONFIG make a
// single-pin change one register write. Every I/O pin has a weak (about
// 100 kΩ) pull-up that is always on, so an undriven input reads 1. The chip
// has a fixed address (0x41), no interrupt, reset or address pin, and no
// register auto-increment: every register is accessed one byte at a time.
type PCA9536Minimal struct {
	conn      connection.RegisterConnection
	shadow    uint8 // shadow of OUTPUT
	direction uint8 // shadow of CONFIG (1 = input)
	polarity  uint8 // shadow of POLARITY
	lastInput uint8 // last INPUT value read (change-detection reference)
}

// NewPCA9536Minimal creates a PCA9536 driver and runs the initialisation
// sequence: OUTPUT ← 0xFF, POLARITY ← 0x00, CONFIG ← 0xFF (all inputs), then
// INPUT is read to initialise the cached input value.
//
// conn must be a configured I²C or SMBus (no PEC) connection bound to the
// device's 7-bit address (fixed 0x41, PCA9536I2CAddress); addr is
// informational.
func NewPCA9536Minimal(conn connection.RegisterConnection, addr uint8) (*PCA9536Minimal, error) {
	_ = addr
	d := &PCA9536Minimal{conn: conn, shadow: 0xFF, direction: 0xFF}
	if err := d.writeReg(pca9536RegOutput, 0xFF); err != nil {
		return nil, err
	}
	if err := d.writeReg(pca9536RegPolarity, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(pca9536RegConfig, 0xFF); err != nil {
		return nil, err
	}
	v, err := d.readReg(pca9536RegInput)
	if err != nil {
		return nil, err
	}
	d.lastInput = v & pca9536Mask
	return d, nil
}

func (d *PCA9536Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *PCA9536Minimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

// ReadPort reads all 4 pins via the INPUT register (actual pin levels, bits
// 7:4 masked off). port is ignored; the chip has a single port.
func (d *PCA9536Minimal) ReadPort(port uint8) (uint8, error) {
	_ = port
	v, err := d.readReg(pca9536RegInput)
	return v & pca9536Mask, err
}

// WritePort writes all 4 output bits via OUTPUT (bits 7:4 are written as 1) and
// updates the shadow. Bits of input pins are stored but have no effect until
// the pin becomes an output. port is ignored; the chip has a single port.
func (d *PCA9536Minimal) WritePort(port uint8, mask uint8) error {
	_ = port
	d.shadow = (mask & pca9536Mask) | 0xF0
	return d.writeReg(pca9536RegOutput, d.shadow)
}

// Pin returns a Pin proxy for pin n (0–3).
func (d *PCA9536Minimal) Pin(n uint8) PCA9536Pin {
	return PCA9536Pin{chip: d, n: n & 3}
}

func (d *PCA9536Minimal) setPin(n uint8, high bool) error {
	if high {
		d.shadow |= 1 << n
	} else {
		d.shadow &^= 1 << n
	}
	return d.writeReg(pca9536RegOutput, d.shadow)
}

// setDirection writes the pin's OUTPUT bit before clearing its CONFIG bit so the
// pin never glitches to a stale level.
func (d *PCA9536Minimal) setDirection(n uint8, input bool) error {
	if input {
		d.direction |= 1 << n
	} else {
		if err := d.writeReg(pca9536RegOutput, d.shadow); err != nil {
			return err
		}
		d.direction &^= 1 << n
	}
	return d.writeReg(pca9536RegConfig, d.direction)
}

// PCA9536Pin is a GPIO proxy for a single pin. It satisfies
// connection.OutputPin (Set), so an output pin can be used directly as the EN
// pin of another chip's connection.
//
// Obtained via (*PCA9536Minimal).Pin.
type PCA9536Pin struct {
	chip *PCA9536Minimal
	n    uint8
}

// Mode sets the pin direction. PCA9536InputPullup is rejected with
// ErrPCA9536NoPullup and nothing is written.
func (p PCA9536Pin) Mode(m PinMode) error {
	if m == PCA9536InputPullup {
		return ErrPCA9536NoPullup
	}
	return p.chip.setDirection(p.n, m != PCA9536Output)
}

// Write sets the output latch to high (true) or low (false).
func (p PCA9536Pin) Write(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Set is Write, satisfying connection.OutputPin.
func (p PCA9536Pin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Read reads the actual logic level at the pin via the INPUT register.
func (p PCA9536Pin) Read() (bool, error) {
	v, err := p.chip.readReg(pca9536RegInput)
	if err != nil {
		return false, err
	}
	return (v>>p.n)&1 == 1, nil
}

// Toggle inverts the output latch bit for this pin.
func (p PCA9536Pin) Toggle() error {
	return p.chip.setPin(p.n, (p.chip.shadow>>p.n)&1 == 0)
}

type pca9536Watch struct {
	trigger connection.Trigger
	handler func(PCA9536FullPin)
}

// PCA9536Full is the PCA9536 driver — full interface. Extends PCA9536Minimal
// with polarity inversion, whole-port direction, polling-based change
// detection and raw register access.
//
// The chip has no interrupt output, so change detection is by polling:
// PollChanges / WatchChanges; Pin.Watch is built on it.
type PCA9536Full struct {
	*PCA9536Minimal

	mu       sync.Mutex
	level    uint8
	callback func(uint8)
	stop     chan struct{}
	interval time.Duration
	watches  [4]*pca9536Watch
}

// NewPCA9536Full creates a PCA9536 full driver with the same initialisation as
// NewPCA9536Minimal.
func NewPCA9536Full(conn connection.RegisterConnection, addr uint8) (*PCA9536Full, error) {
	m, err := NewPCA9536Minimal(conn, addr)
	if err != nil {
		return nil, err
	}
	return &PCA9536Full{PCA9536Minimal: m, level: m.lastInput, interval: 5 * time.Millisecond}, nil
}

func (d *PCA9536Full) refreshInput() error {
	v, err := d.readReg(pca9536RegInput)
	if err != nil {
		return err
	}
	d.lastInput = v & pca9536Mask
	return nil
}

// ConfigurePolarity writes POLARITY: mask bit n = 1 inverts the INPUT bit of Pn.
// The cached input value is refreshed, so the polarity change itself is not
// reported by PollChanges.
func (d *PCA9536Full) ConfigurePolarity(mask uint8) error {
	d.polarity = mask & pca9536Mask
	if err := d.writeReg(pca9536RegPolarity, d.polarity); err != nil {
		return err
	}
	return d.refreshInput()
}

// GetDirection returns the shadow CONFIG register (1 = input), 4 bits.
func (d *PCA9536Full) GetDirection() uint8 {
	return d.direction & pca9536Mask
}

// SetDirection writes CONFIG in one transaction (1 = input, 0 = output). OUTPUT
// is written first so pins turning into outputs never glitch; the cached input
// value is refreshed afterwards.
func (d *PCA9536Full) SetDirection(mask uint8) error {
	if err := d.writeReg(pca9536RegOutput, d.shadow); err != nil {
		return err
	}
	d.direction = (mask & pca9536Mask) | 0xF0
	if err := d.writeReg(pca9536RegConfig, d.direction); err != nil {
		return err
	}
	return d.refreshInput()
}

// ReadRegister reads a raw register 0–3 (debug / escape hatch).
func (d *PCA9536Full) ReadRegister(reg uint8) (uint8, error) {
	if reg > 3 {
		return 0, errors.New("pca9536: register must be 0-3")
	}
	return d.readReg(reg)
}

// WriteRegister writes a raw register 1–3; the OUTPUT / POLARITY / CONFIG
// shadows are kept in sync. Writing register 0 (INPUT, read-only) or any other
// address returns an error.
func (d *PCA9536Full) WriteRegister(reg, value uint8) error {
	switch reg {
	case pca9536RegOutput:
		d.shadow = value
	case pca9536RegPolarity:
		d.polarity = value
	case pca9536RegConfig:
		d.direction = value
	default:
		return errors.New("pca9536: register must be 1-3 (INPUT is read-only)")
	}
	return d.writeReg(reg, value)
}

// Reset writes the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF) and
// refreshes the input cache. The chip has no RESET pin; only a power cycle
// clears a lock-up.
func (d *PCA9536Full) Reset() error {
	d.UnwatchChanges()
	if err := d.writeReg(pca9536RegOutput, 0xFF); err != nil {
		return err
	}
	if err := d.writeReg(pca9536RegPolarity, 0x00); err != nil {
		return err
	}
	if err := d.writeReg(pca9536RegConfig, 0xFF); err != nil {
		return err
	}
	d.shadow, d.polarity, d.direction = 0xFF, 0x00, 0xFF
	if err := d.refreshInput(); err != nil {
		return err
	}
	d.level = d.lastInput
	return nil
}

// PCA9536FullPin is a GPIO proxy for a single pin obtained via
// (*PCA9536Full).Pin — adds Watch/Unwatch and satisfies connection.InputPin
// (OnEdge, IsHigh) as well as connection.OutputPin. There is no SetPullUp: the
// weak pull-up is fixed in hardware.
type PCA9536FullPin struct {
	PCA9536Pin
	full *PCA9536Full
}

// Pin returns a Full pin proxy for pin n (0–3).
func (d *PCA9536Full) Pin(n uint8) PCA9536FullPin {
	return PCA9536FullPin{PCA9536Pin: d.PCA9536Minimal.Pin(n), full: d}
}

// IsHigh reads the actual pin level, satisfying connection.InputPin.
func (p PCA9536FullPin) IsHigh() (bool, error) {
	return p.Read()
}

// Watch subscribes handler to changes on this pin matching trigger. The chip
// reports changes only; the rising/falling filter is applied in software.
// A polling goroutine (5 ms) is started on first use. At most one handler per
// pin; a second Watch replaces the first. Output pins never report changes, so
// Watch on one returns an error.
func (p PCA9536FullPin) Watch(trigger connection.Trigger, handler func(PCA9536FullPin)) error {
	if (p.full.direction>>p.n)&1 == 0 {
		return errors.New("pca9536: cannot watch an output pin")
	}
	p.full.mu.Lock()
	p.full.watches[p.n] = &pca9536Watch{trigger: trigger, handler: handler}
	running := p.full.stop != nil
	p.full.mu.Unlock()
	if !running {
		return p.full.WatchChanges(nil, p.full.interval)
	}
	return nil
}

// Unwatch removes this pin's handler, if any.
func (p PCA9536FullPin) Unwatch() error {
	p.full.mu.Lock()
	p.full.watches[p.n] = nil
	p.full.mu.Unlock()
	return nil
}

// OnEdge registers handler for edges on this pin, satisfying
// connection.InputPin, and returns a function that removes it.
func (p PCA9536FullPin) OnEdge(trigger connection.Trigger, handler func()) (unsubscribe func()) {
	if err := p.Watch(trigger, func(PCA9536FullPin) { handler() }); err != nil {
		return func() {}
	}
	return func() { _ = p.Unwatch() }
}

// WatchChanges starts a polling goroutine that calls PollChanges every interval
// and invokes callback(changed) when the mask is non-zero (callback may be nil
// when only per-pin watches are used). UnwatchChanges stops it.
func (d *PCA9536Full) WatchChanges(callback func(changed uint8), interval time.Duration) error {
	if interval <= 0 {
		interval = 5 * time.Millisecond
	}
	d.UnwatchChanges()
	d.mu.Lock()
	d.callback = callback
	d.interval = interval
	stop := make(chan struct{})
	d.stop = stop
	d.mu.Unlock()
	go func() {
		t := time.NewTicker(interval)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				d.dispatch()
			}
		}
	}()
	return nil
}

// UnwatchChanges stops the polling goroutine started by WatchChanges (or by a
// pin Watch). Per-pin watches stay registered.
func (d *PCA9536Full) UnwatchChanges() {
	d.mu.Lock()
	stop := d.stop
	d.stop = nil
	d.callback = nil
	d.mu.Unlock()
	if stop != nil {
		close(stop)
	}
}

// PollChanges reads INPUT and returns the changed-pin mask (software edge
// detection; the chip has no interrupt output): the XOR of the new and the
// cached INPUT value, limited to pins configured as inputs. The cache is
// updated.
func (d *PCA9536Full) PollChanges() (uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	now, err := d.readReg(pca9536RegInput)
	if err != nil {
		return 0, err
	}
	now &= pca9536Mask
	changed := (now ^ d.lastInput) & d.direction & pca9536Mask
	d.lastInput = now
	d.level = now
	return changed, nil
}

// dispatch polls once and dispatches the callback and any matching per-pin
// watches.
func (d *PCA9536Full) dispatch() {
	changed, err := d.PollChanges()
	if err != nil || changed == 0 {
		return
	}

	d.mu.Lock()
	cb := d.callback
	level := d.level
	type call struct {
		fn  func(PCA9536FullPin)
		pin PCA9536FullPin
	}
	var calls []call
	for n := uint8(0); n < 4; n++ {
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

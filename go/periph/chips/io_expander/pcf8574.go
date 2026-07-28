// Package ioexpander contains drivers for I/O expander chips (PCF8574,
// PCF8575, MCP23017, etc.) over I²C.
//
// The defining characteristic of an IO expander driver is that it exposes
// individual Pin objects that implement each platform's GPIO interface
// surface — users obtain a pin from the driver and use it as if it were
// a hardware GPIO pin. The Pin type holds a plain pointer back to the
// driver; Go's lack of a borrow checker means no RefCell equivalent is
// needed, unlike the Rust implementation.
//
// Package name drops the underscore present in the directory name
// (io_expander → ioexpander) to satisfy go vet / staticcheck.
package ioexpander

import (
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCF8574Minimal is the 8-bit quasi-bidirectional I/O port expander driver
// — minimal interface.
//
// Communicates over I²C at up to 100 kHz standard mode. Direction is
// implicit: writing 1 puts a pin in input mode (weak ~100 µA current
// source from the chip's pull-up); writing 0 drives the pin strongly
// low (up to 25 mA open-drain sink). A shadow register in the driver
// tracks the output latch so individual bits can be set without a
// read-modify-write bus transaction.
//
// At construction all pins are initialised to input mode (shadow = 0xFF).
//
// Two address-range variants share identical behaviour:
//   - PCF8574  — 0x20–0x27 (default 0x20; A2=A1=A0=0)
//   - PCF8574A — 0x38–0x3F (default 0x38; A2=A1=A0=0); overlaps common
//     OLED display address range
type PCF8574Minimal struct {
	connection connection.Connection
	addr       uint8
	// shadow is the output latch mirror. Bit n = last value written to pin n.
	shadow uint8
}

// NewPCF8574Minimal creates a new PCF8574Minimal and sets all eight pins
// to quasi-bidirectional input mode (writes 0xFF to the bus).
//
// conn must be a configured I²C connection bound to the device's
// 7-bit address (0x20 for PCF8574, 0x38 for PCF8574A).
func NewPCF8574Minimal(conn connection.Connection, addr uint8) (*PCF8574Minimal, error) {
	d := &PCF8574Minimal{connection: conn, addr: addr, shadow: 0xFF}
	if err := d.writePort(0xFF); err != nil {
		return nil, err
	}
	return d, nil
}

// ReadPort reads all 8 pins as a bitmask.
//
// The port argument is accepted for cross-compatibility with PCF8575 but
// is ignored here — the PCF8574 has exactly one 8-bit port. Returns the
// actual logic level at each pin.
//
// Bit 0 = P0, bit 7 = P7.
func (d *PCF8574Minimal) ReadPort(port uint8) (uint8, error) {
	buf, err := d.connection.Read(1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// ReadPort0 is equivalent to ReadPort(0), the only port on the device.
func (d *PCF8574Minimal) ReadPort0() (uint8, error) {
	return d.ReadPort(0)
}

// WritePort writes all 8 pins at once and updates the shadow register.
//
// mask bit n = 1 → input mode (weak pull-up); bit n = 0 → drive low.
// The port argument is accepted for cross-compatibility with PCF8575 but
// is ignored here.
func (d *PCF8574Minimal) WritePort(port uint8, mask uint8) error {
	return d.writePort(mask)
}

// WritePort0 is equivalent to WritePort(0, mask).
func (d *PCF8574Minimal) WritePort0(mask uint8) error {
	return d.writePort(mask)
}

func (d *PCF8574Minimal) writePort(mask uint8) error {
	d.shadow = mask
	return d.connection.Write([]byte{mask})
}

// Pin returns a Pin proxy for pin n (0–7).
//
// The returned Pin holds a back-reference to this driver and performs
// its own I²C transactions on each operation — no state is cached on
// the Pin.
func (d *PCF8574Minimal) Pin(n uint8) PCF8574Pin {
	return PCF8574Pin{chip: d, n: n}
}

// setPin is the internal helper that mutates the shadow and writes the
// full byte. Exposed so PCF8574Full can reuse the same path.
func (d *PCF8574Minimal) setPin(n uint8, high bool) error {
	if high {
		d.shadow |= 1 << n
	} else {
		d.shadow &^= 1 << n
	}
	return d.connection.Write([]byte{d.shadow})
}

// PCF8574Pin is a GPIO proxy for a single PCF8574 pin.
//
// Obtained via (*PCF8574Minimal).Pin. The Pin holds a plain pointer back
// to the driver — Go's lack of a borrow checker means no RefCell is
// needed to share the connection across multiple Pin objects.
type PCF8574Pin struct {
	chip *PCF8574Minimal
	n    uint8
}

// Get reads the actual logic level at the pin. Performs a bus read
// each call. Returns true if the pin is high.
func (p PCF8574Pin) Get() (bool, error) {
	buf, err := p.chip.connection.Read(1)
	if err != nil {
		return false, err
	}
	return ((buf[0] >> p.n) & 1) == 1, nil
}

// Set writes 1 (high — releases pin to quasi-input mode) or 0
// (low — drives pin low via the 25 mA open-drain sink).
func (p PCF8574Pin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Toggle inverts the shadow bit for this pin. If the last written
// value was 1 (input mode) the pin is driven low; if the last
// written value was 0 (driven low) the pin is released to input.
func (p PCF8574Pin) Toggle() error {
	high := ((p.chip.shadow >> p.n) & 1) == 0
	return p.chip.setPin(p.n, high)
}

// pcf8574Watch is the per-pin bookkeeping record for PCF8574Full.Pin.Watch.
type pcf8574Watch struct {
	trigger connection.Trigger
	handler func(PCF8574FullPin)
	pin     PCF8574FullPin
}

// PCF8574Full is the PCF8574 driver — full interface. Extends
// PCF8574Minimal with interrupt-on-change support: chip-level
// OnInterrupt/OffInterrupt/PollInterrupt, plus per-pin Watch/Unwatch on
// PCF8574FullPin.
//
// The previous-read state is tracked internally; PollInterrupt returns
// the bitmask of pins that changed since the last call and updates the
// stored previous value. Reading PORT_IN via the bus also clears the
// chip's active-low INT output.
type PCF8574Full struct {
	*PCF8574Minimal
	prev uint8

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	watches     [8]*pcf8574Watch
}

// NewPCF8574Full creates a new PCF8574Full, sets all pins to input
// mode, and seeds the previous-read state for interrupt comparison.
func NewPCF8574Full(conn connection.Connection, addr uint8) (*PCF8574Full, error) {
	m, err := NewPCF8574Minimal(conn, addr)
	if err != nil {
		return nil, err
	}
	port, err := m.ReadPort0()
	if err != nil {
		return nil, err
	}
	return &PCF8574Full{PCF8574Minimal: m, prev: port}, nil
}

// PCF8574FullPin is a GPIO proxy for a single PCF8574 pin obtained via
// (*PCF8574Full).Pin — adds Watch/Unwatch to the base PCF8574Pin.
type PCF8574FullPin struct {
	PCF8574Pin
	full *PCF8574Full
}

// Pin returns a Pin proxy for pin n (0–7) backed by this driver's
// underlying connection, with Watch/Unwatch support.
func (d *PCF8574Full) Pin(n uint8) PCF8574FullPin {
	return PCF8574FullPin{PCF8574Pin: d.PCF8574Minimal.Pin(n), full: d}
}

// Watch subscribes handler to edges on this pin matching trigger. Requires
// OnInterrupt to have been called on the chip first (or a real hardware
// INT line wired via the Connection) so edges are actually delivered. At
// most one handler per pin; a second Watch call replaces the first.
func (p PCF8574FullPin) Watch(trigger connection.Trigger, handler func(PCF8574FullPin)) error {
	p.full.mu.Lock()
	p.full.watches[p.n] = &pcf8574Watch{trigger: trigger, handler: handler, pin: p}
	p.full.mu.Unlock()
	return nil
}

// Unwatch removes this pin's handler, if any.
func (p PCF8574FullPin) Unwatch() error {
	p.full.mu.Lock()
	p.full.watches[p.n] = nil
	p.full.mu.Unlock()
	return nil
}

// OnInterrupt subscribes callback to be called with the changed-pin
// bitmask on each INT assertion. Uses the connection's InputPin if wired,
// otherwise falls back to a 5 ms polling goroutine.
func (d *PCF8574Full) OnInterrupt(callback func(uint8)) error {
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

	pin := d.connection.IntPin()
	if pin == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.pollPin = poll
		pin = poll
	}
	d.mu.Unlock()

	d.unsubscribe = pin.OnEdge(connection.Falling, func() { d.handleEdge() })
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *PCF8574Full) OffInterrupt() error {
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

// PollInterrupt reads the current port, returns the bitmask of pins
// that changed since the previous call, and updates the stored
// previous value. Reading also clears the chip's INT output.
func (d *PCF8574Full) PollInterrupt() (uint8, error) {
	current, err := d.PCF8574Minimal.ReadPort0()
	if err != nil {
		return 0, err
	}
	d.mu.Lock()
	changed := current ^ d.prev
	d.prev = current
	d.mu.Unlock()
	return changed, nil
}

// handleEdge is called on each INT edge (real or polled). It reads and
// clears the interrupt, then dispatches the chip-level callback and any
// matching per-pin watches.
func (d *PCF8574Full) handleEdge() {
	changed, err := d.PollInterrupt()
	if err != nil {
		return
	}

	d.mu.Lock()
	current := d.prev
	cb := d.callback
	type call struct {
		fn  func(PCF8574FullPin)
		pin PCF8574FullPin
	}
	var calls []call
	for n := uint8(0); n < 8; n++ {
		w := d.watches[n]
		if w == nil || (changed>>n)&1 == 0 {
			continue
		}
		high := (current>>n)&1 == 1
		fire := w.trigger == connection.Change ||
			(w.trigger == connection.Rising && high) ||
			(w.trigger == connection.Falling && !high)
		if fire {
			calls = append(calls, call{fn: w.handler, pin: w.pin})
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

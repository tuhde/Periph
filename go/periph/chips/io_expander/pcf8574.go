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

import "github.com/tuhde/Periph/go/periph/transport"

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
	transport transport.Transport
	addr      uint8
	// shadow is the output latch mirror. Bit n = last value written to pin n.
	shadow uint8
}

// NewPCF8574Minimal creates a new PCF8574Minimal and sets all eight pins
// to quasi-bidirectional input mode (writes 0xFF to the bus).
//
// transport must be a configured I²C transport bound to the device's
// 7-bit address (0x20 for PCF8574, 0x38 for PCF8574A).
func NewPCF8574Minimal(t transport.Transport, addr uint8) (*PCF8574Minimal, error) {
	d := &PCF8574Minimal{transport: t, addr: addr, shadow: 0xFF}
	if err := d.writePort(0xFF); err != nil {
		return nil, err
	}
	return d, nil
}

// ReadPort reads all 8 pins as a bitmask.
//
// The port argument is accepted for cross-compatibility with PCF8575 but
// is ignored here — the PCF8574 has exactly one 8-bit port. Returns the
// actual logic level at each pin (not the shadow register).
//
// Bit 0 = P0, bit 7 = P7.
func (d *PCF8574Minimal) ReadPort(port uint8) (uint8, error) {
	buf, err := d.transport.Read(1)
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
	return d.transport.Write([]byte{mask})
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
	return d.transport.Write([]byte{d.shadow})
}

// PCF8574Pin is a GPIO proxy for a single PCF8574 pin.
//
// Obtained via (*PCF8574Minimal).Pin or (*PCF8574Full).Pin. The Pin
// holds a plain pointer back to the driver — Go's lack of a borrow
// checker means no RefCell is needed to share the transport across
// multiple Pin objects.
type PCF8574Pin struct {
	chip *PCF8574Minimal
	n    uint8
}

// Get reads the actual logic level at the pin. Performs a bus read
// each call. Returns true if the pin is high.
func (p PCF8574Pin) Get() (bool, error) {
	buf, err := p.chip.transport.Read(1)
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

// PCF8574Full is the PCF8574 driver — full interface. Extends
// PCF8574Minimal with interrupt-on-change support.
//
// The previous-read state is tracked internally; ClearInterrupt
// returns the bitmask of pins that changed since the last call and
// updates the stored previous value. Reading PORT_IN via the bus
// also clears the chip's active-low INT output.
type PCF8574Full struct {
	*PCF8574Minimal
	prev uint8
}

// NewPCF8574Full creates a new PCF8574Full, sets all pins to input
// mode, and seeds the previous-read state for interrupt comparison.
func NewPCF8574Full(t transport.Transport, addr uint8) (*PCF8574Full, error) {
	m, err := NewPCF8574Minimal(t, addr)
	if err != nil {
		return nil, err
	}
	port, err := m.ReadPort0()
	if err != nil {
		return nil, err
	}
	return &PCF8574Full{PCF8574Minimal: m, prev: port}, nil
}

// Pin returns a Pin proxy for pin n (0–7) backed by this driver's
// underlying transport. Behaves identically to a Minimal Pin.
func (d *PCF8574Full) Pin(n uint8) PCF8574Pin {
	return d.PCF8574Minimal.Pin(n)
}

// ClearInterrupt reads the current port, returns the bitmask of pins
// that changed since the previous call, and updates the stored
// previous value. The act of reading also clears the chip's INT
// output (the chip clears INT on any I²C read).
func (d *PCF8574Full) ClearInterrupt() (uint8, error) {
	current, err := d.PCF8574Minimal.ReadPort0()
	if err != nil {
		return 0, err
	}
	changed := current ^ d.prev
	d.prev = current
	return changed, nil
}

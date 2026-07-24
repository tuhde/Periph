package ioexpander

import "github.com/tuhde/Periph/go/periph/transport"

// PCF8575Minimal is the 16-bit quasi-bidirectional I/O port expander
// driver — minimal interface.
//
// Communicates over I²C at up to 400 kHz Fast mode. The 16 pins are
// organised as two independent 8-bit ports: Port 0 (P00–P07) and
// Port 1 (P10–P17). Like the PCF8574, direction is implicit — writing
// 1 enables the internal 100 µA pull-up (input/high-impedance) and
// writing 0 drives the pin strongly low (open-drain output, up to
// 25 mA sink). Two shadow registers track the output latches.
//
// At construction all 16 pins are initialised to input mode
// (shadow = [0xFF, 0xFF]).
//
// The device always transfers exactly 2 bytes per I²C transaction —
// ReadPort and WritePort issue a full 2-byte I²C transfer, preserving
// the other port's shadow value on writes.
type PCF8575Minimal struct {
	transport transport.Transport
	addr      uint8
	// shadow[0] covers pins 0–7 (Port 0), shadow[1] covers pins 8–15 (Port 1).
	shadow [2]uint8
}

// NewPCF8575Minimal creates a new PCF8575Minimal and sets all 16 pins
// to quasi-bidirectional input mode (writes [0xFF, 0xFF] to the bus).
//
// transport must be a configured I²C transport bound to the device's
// 7-bit address (0x20–0x27; default 0x20 with A2=A1=A0=0).
func NewPCF8575Minimal(t transport.Transport, addr uint8) (*PCF8575Minimal, error) {
	d := &PCF8575Minimal{transport: t, addr: addr, shadow: [2]uint8{0xFF, 0xFF}}
	if err := d.writeBoth(); err != nil {
		return nil, err
	}
	return d, nil
}

// ReadPort reads all 8 pins of the given port (0 or 1) as a bitmask.
// Always performs a full 2-byte I²C read; returns the byte for the
// requested port. Returns the actual logic level at each pin.
func (d *PCF8575Minimal) ReadPort(port uint8) (uint8, error) {
	buf, err := d.transport.Read(2)
	if err != nil {
		return 0, err
	}
	return buf[port&1], nil
}

// WritePort writes all 8 pins of the given port (0 or 1) at once and
// updates the shadow register. Always performs a full 2-byte I²C write,
// preserving the other port's shadow value.
func (d *PCF8575Minimal) WritePort(port uint8, mask uint8) error {
	d.shadow[port&1] = mask
	return d.writeBoth()
}

func (d *PCF8575Minimal) writeBoth() error {
	return d.transport.Write([]byte{d.shadow[0], d.shadow[1]})
}

func (d *PCF8575Minimal) readBoth() ([2]uint8, error) {
	buf, err := d.transport.Read(2)
	if err != nil {
		return [2]uint8{}, err
	}
	return [2]uint8{buf[0], buf[1]}, nil
}

// Pin returns a Pin proxy for pin n (0–15). port = n/8, bit = n%8.
func (d *PCF8575Minimal) Pin(n uint8) PCF8575Pin {
	return PCF8575Pin{chip: d, n: n}
}

// setPin is the internal helper that mutates the shadow and writes
// both bytes.
func (d *PCF8575Minimal) setPin(n uint8, high bool) error {
	portIdx := n >> 3
	bit := n & 7
	if high {
		d.shadow[portIdx] |= 1 << bit
	} else {
		d.shadow[portIdx] &^= 1 << bit
	}
	return d.writeBoth()
}

// PCF8575Pin is a GPIO proxy for a single PCF8575 pin.
//
// Obtained via (*PCF8575Minimal).Pin or (*PCF8575Full).Pin. The Pin
// holds a plain pointer back to the driver.
type PCF8575Pin struct {
	chip *PCF8575Minimal
	n    uint8
}

// Get reads the actual logic level at the pin. Performs a full 2-byte
// bus read; returns the bit for this pin.
func (p PCF8575Pin) Get() (bool, error) {
	buf, err := p.chip.readBoth()
	if err != nil {
		return false, err
	}
	portIdx := p.n >> 3
	bit := p.n & 7
	return ((buf[portIdx] >> bit) & 1) == 1, nil
}

// Set writes 1 (high — release to quasi-input) or 0 (low — drive low
// via open-drain sink).
func (p PCF8575Pin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Toggle inverts the shadow bit for this pin.
func (p PCF8575Pin) Toggle() error {
	portIdx := p.n >> 3
	bit := p.n & 7
	high := ((p.chip.shadow[portIdx] >> bit) & 1) == 0
	return p.chip.setPin(p.n, high)
}

// PCF8575Full is the PCF8575 driver — full interface. Extends
// PCF8575Minimal with interrupt-on-change support.
//
// The previous-read state is tracked internally; ClearInterrupt returns
// a 16-bit bitmask of pins that changed since the last call (bits 0–7
// = Port 0, bits 8–15 = Port 1) and updates the stored previous value.
// Any I²C read clears the chip's INT output.
type PCF8575Full struct {
	*PCF8575Minimal
	prev [2]uint8
}

// NewPCF8575Full creates a new PCF8575Full, sets all 16 pins to input
// mode, and seeds the previous-read state.
func NewPCF8575Full(t transport.Transport, addr uint8) (*PCF8575Full, error) {
	m, err := NewPCF8575Minimal(t, addr)
	if err != nil {
		return nil, err
	}
	both, err := m.readBoth()
	if err != nil {
		return nil, err
	}
	return &PCF8575Full{PCF8575Minimal: m, prev: both}, nil
}

// Pin returns a Pin proxy for pin n (0–15) backed by this driver's
// underlying transport.
func (d *PCF8575Full) Pin(n uint8) PCF8575Pin {
	return d.PCF8575Minimal.Pin(n)
}

// ClearInterrupt reads both ports, returns the 16-bit bitmask of pins
// that changed since the previous call, and updates the stored
// previous values. Bits 0–7 = Port 0 changed, bits 8–15 = Port 1
// changed. Reading also clears the chip's INT output.
func (d *PCF8575Full) ClearInterrupt() (uint16, error) {
	current, err := d.PCF8575Minimal.readBoth()
	if err != nil {
		return 0, err
	}
	changed0 := current[0] ^ d.prev[0]
	changed1 := current[1] ^ d.prev[1]
	d.prev = current
	return uint16(changed0) | (uint16(changed1) << 8), nil
}

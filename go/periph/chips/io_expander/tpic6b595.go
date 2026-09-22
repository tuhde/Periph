package ioexpander

import "fmt"

// TPIC6B595Minimal is the 8-bit power SIPO shift register driver —
// minimal interface.
//
// Drives up to numDevices cascaded TPIC6B595s through a SiPo
// (serial-in/parallel-out) connection. Each device exposes 8 open-drain
// outputs (DRAIN0–DRAIN7); every write shifts the entire cascade
// MSB-first and pulses RCK to latch all outputs atomically. Outputs
// only sink current — they never source it; an external pull-up or
// load supply is required for the "off"/high state.
//
// The driver owns a numDevices-byte shadow register so single-pin
// updates work without re-reading the bus. Every write (pin, port, fill)
// rebuilds and retransmits the entire reversed cascade — see
// specs/io_expander/tpic6b595.md for the wire-order reversal that
// cascading requires.
//
// Initialises every output to OFF at construction (shadow zero, latched
// once). If the SiPo connection has SRCLR wired, the constructor
// pulses it to clear the shift register before the all-zero latch.
type TPIC6B595Minimal struct {
	sipo        SiPo
	numDevices  uint8
	shadow      [8]uint8 // up to 8 cascaded devices
}

// MaxTPIC6B595Devices is the maximum cascade depth supported by the
// driver's shadow buffer.
const MaxTPIC6B595Devices = 8

// NewTPIC6B595Minimal creates a new TPIC6B595Minimal and clears every
// output.
//
// sipo must be a configured SiPo connection (a *connection.SIPOConnection
// from periph/connection). numDevices is the number of cascaded
// TPIC6B595s on the wire (1–8).
func NewTPIC6B595Minimal(sipo SiPo, numDevices uint8) (*TPIC6B595Minimal, error) {
	if numDevices == 0 || numDevices > MaxTPIC6B595Devices {
		return nil, fmt.Errorf("tpic6b595: numDevices must be 1..%d, got %d", MaxTPIC6B595Devices, numDevices)
	}
	d := &TPIC6B595Minimal{sipo: sipo, numDevices: numDevices}
	// Best-effort clear of the shift register; missing SRCLR is fine.
	_ = d.sipo.Clear()
	if err := d.flush(); err != nil {
		return nil, err
	}
	return d, nil
}

// WritePort writes all 8 outputs of cascaded device port from mask.
// mask bit 0 = DRAIN0, bit 7 = DRAIN7. 1 = ON (DMOS conducting, sinks
// current); 0 = OFF (high-impedance).
func (d *TPIC6B595Minimal) WritePort(port uint8, mask uint8) error {
	d.shadow[port] = mask
	return d.flush()
}

// Fill sets every pin on every cascaded device to value.
func (d *TPIC6B595Minimal) Fill(value bool) error {
	b := uint8(0x00)
	if value {
		b = 0xFF
	}
	for i := uint8(0); i < d.numDevices; i++ {
		d.shadow[i] = b
	}
	return d.flush()
}

// Off turns every output off (equivalent to Fill(false)).
func (d *TPIC6B595Minimal) Off() error {
	return d.Fill(false)
}

// Pin returns a TPIC6B595Pin proxy for global pin n (0..numDevices*8 - 1).
func (d *TPIC6B595Minimal) Pin(n uint8) TPIC6B595Pin {
	return TPIC6B595Pin{chip: d, n: n}
}

// Shadow returns the shadow byte for the given cascaded device port.
// Test-only — production callers should use Pin / Get / Set instead.
func (d *TPIC6B595Minimal) Shadow(port uint8) uint8 {
	return d.shadow[port]
}

// flush builds the reversed wire-order buffer from the shadow register
// and writes it via the SiPo connection.
func (d *TPIC6B595Minimal) flush() error {
	wire := make([]byte, d.numDevices)
	for i := uint8(0); i < d.numDevices; i++ {
		wire[i] = d.shadow[d.numDevices-1-i]
	}
	return d.sipo.Write(wire)
}

// TPIC6B595Pin is a GPIO proxy for a single TPIC6B595 pin — output-only.
type TPIC6B595Pin struct {
	chip *TPIC6B595Minimal
	n    uint8
}

// Get returns the shadow bit for this pin (NOT a bus read — SiPo is
// write-only). Returns 0 or 1.
func (p TPIC6B595Pin) Get() (bool, error) {
	port := p.n >> 3
	bit := p.n & 7
	return (p.chip.shadow[port]>>bit)&1 == 1, nil
}

// Set drives the DMOS output ON (high=true) or OFF (high=false).
func (p TPIC6B595Pin) Set(high bool) error {
	port := p.n >> 3
	bit := p.n & 7
	if high {
		p.chip.shadow[port] |= 1 << bit
	} else {
		p.chip.shadow[port] &^= 1 << bit
	}
	return p.chip.flush()
}

// Toggle inverts the shadow bit for this pin.
func (p TPIC6B595Pin) Toggle() error {
	v, err := p.Get()
	if err != nil {
		return err
	}
	return p.Set(!v)
}

// TPIC6B595Full extends TPIC6B595Minimal with hardware features.
type TPIC6B595Full struct {
	*TPIC6B595Minimal
}

// NewTPIC6B595Full creates a new TPIC6B595Full and clears every output.
func NewTPIC6B595Full(sipo SiPo, numDevices uint8) (*TPIC6B595Full, error) {
	min, err := NewTPIC6B595Minimal(sipo, numDevices)
	if err != nil {
		return nil, err
	}
	return &TPIC6B595Full{TPIC6B595Minimal: min}, nil
}

// Clear pulses SRCLR to clear the shift register only. The storage
// register (and therefore the DRAIN outputs) keeps its last-latched
// value until the next RCK pulse.
func (d *TPIC6B595Full) Clear() error {
	return d.sipo.Clear()
}

// SetOutputEnable drives G LOW (enabled=true) or HIGH (enabled=false).
// enabled=false forces every output off without disturbing the shadow
// register or the storage register's contents.
func (d *TPIC6B595Full) SetOutputEnable(enabled bool) error {
	return d.sipo.SetOutputEnable(enabled)
}

// WriteAll writes every cascaded device's byte in one call. values is
// length-truncated or zero-extended to numDevices as needed.
func (d *TPIC6B595Full) WriteAll(values []uint8) error {
	for i := uint8(0); i < d.numDevices; i++ {
		var v uint8
		if int(i) < len(values) {
			v = values[i]
		}
		d.shadow[i] = v
	}
	return d.flush()
}

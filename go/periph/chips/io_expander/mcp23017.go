package ioexpander

import "github.com/tuhde/Periph/go/periph/transport"

// MCP23017 register addresses — IOCON.BANK = 0 (power-on default).
const (
	mcpRegIODIRA  uint8 = 0x00
	mcpRegIODIRB  uint8 = 0x01
	mcpRegIPOLA   uint8 = 0x02
	mcpRegIPOLB   uint8 = 0x03
	mcpRegGPINTENA uint8 = 0x04
	mcpRegGPINTENB uint8 = 0x05
	mcpRegDEFVALA uint8 = 0x06
	mcpRegDEFVALB uint8 = 0x07
	mcpRegINTCONA uint8 = 0x08
	mcpRegINTCONB uint8 = 0x09
	mcpRegIOCON   uint8 = 0x0A
	mcpRegGPPUA   uint8 = 0x0C
	mcpRegGPPUB   uint8 = 0x0D
	mcpRegINTFA   uint8 = 0x0E
	mcpRegINTFB   uint8 = 0x0F
	mcpRegINTCAPA uint8 = 0x10
	mcpRegINTCAPB uint8 = 0x11
	mcpRegGPIOA   uint8 = 0x12
	mcpRegGPIOB   uint8 = 0x13
	mcpRegOLATA   uint8 = 0x14
	mcpRegOLATB   uint8 = 0x15
)

// MCP23017Minimal is the 16-bit bidirectional I/O port expander driver
// — minimal interface.
//
// Communicates over I²C at up to 1.7 MHz Fast-mode-plus. The 16 GPIO
// pins are split into two 8-bit ports (PORTA: pins 0–7, PORTB: pins
// 8–15). Direction is controlled explicitly per-pin via IODIR (1 =
// input, 0 = output). Each pin can source or sink up to 25 mA.
//
// At construction:
//   - OLATA = OLATB = 0x00 (output latches cleared)
//   - IODIRA = IODIRB = 0x7F (pins 0–6 input, pins 7 output-only as
//     required by the hardware; GPA7/GPB7 are physically output-only)
//   - IPOLA/B = GPPUA/B = 0x00 (no inversion, no pull-ups)
//   - All interrupts disabled
//
// GPA7 (pin 7) and GPB7 (pin 15) are output-only on the hardware.
// Configuring them as inputs is not possible.
type MCP23017Minimal struct {
	transport transport.Transport
	addr      uint8
	// shadow tracks OLATA and OLATB. Bit n in shadow[0] = last value
	// written to OLATA bit n (pins 0–7). Bit n in shadow[1] = OLATB.
	shadow [2]uint8
}

// NewMCP23017Minimal creates a new MCP23017Minimal and performs the
// mandatory initialisation sequence: clear OLAT, set IODIR = 0x7F for
// both ports (pins 0–6 input, pins 7 output), and zero IPOL/GPPU.
//
// transport must be a configured I²C transport bound to the device's
// 7-bit address (0x20–0x27; default 0x20 with A2=A1=A0=0).
func NewMCP23017Minimal(t transport.Transport, addr uint8) (*MCP23017Minimal, error) {
	d := &MCP23017Minimal{transport: t, addr: addr}
	if err := d.writeReg(mcpRegOLATA, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(mcpRegOLATB, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(mcpRegIODIRA, 0x7F); err != nil {
		return nil, err
	}
	if err := d.writeReg(mcpRegIODIRB, 0x7F); err != nil {
		return nil, err
	}
	if err := d.writeReg(mcpRegIPOLA, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(mcpRegIPOLB, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(mcpRegGPPUA, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(mcpRegGPPUB, 0x00); err != nil {
		return nil, err
	}
	return d, nil
}

// writeReg writes a single register.
func (d *MCP23017Minimal) writeReg(reg, value uint8) error {
	return d.transport.Write([]byte{reg, value})
}

// readReg reads a single register.
func (d *MCP23017Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.transport.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// ConfigureDirection sets the IODIR register for the given port.
// mask bit n = 1 → pin n is input, bit n = 0 → pin n is output.
// Note: pins 7 and 15 (GPA7/GPB7) are output-only on the hardware; their
// IODIR bits must remain 0.
func (d *MCP23017Minimal) ConfigureDirection(port uint8, mask uint8) error {
	return d.writeReg(mcpRegIODIRA+(port&1), mask)
}

// ReadPort reads all 8 pins of the given port (0 = PORTA, 1 = PORTB)
// via the GPIO register. Returns the actual logic level at each pin.
func (d *MCP23017Minimal) ReadPort(port uint8) (uint8, error) {
	return d.readReg(mcpRegGPIOA + (port & 1))
}

// WritePort writes all 8 output pins of the given port via the output
// latch (OLATA/OLATB) and updates the in-memory shadow register.
func (d *MCP23017Minimal) WritePort(port uint8, mask uint8) error {
	d.shadow[port&1] = mask
	return d.writeReg(mcpRegOLATA+(port&1), mask)
}

// Pin returns a Pin proxy for pin n (0–15). 0–7 = PORTA, 8–15 = PORTB.
func (d *MCP23017Minimal) Pin(n uint8) MCP23017Pin {
	return MCP23017Pin{chip: d, n: n}
}

// setPin is the internal helper that mutates the shadow and writes
// the corresponding OLAT register.
func (d *MCP23017Minimal) setPin(n uint8, high bool) error {
	port := n >> 3
	bit := n & 7
	if high {
		d.shadow[port] |= 1 << bit
	} else {
		d.shadow[port] &^= 1 << bit
	}
	return d.writeReg(mcpRegOLATA+uint8(port), d.shadow[port])
}

// MCP23017Pin is a GPIO proxy for a single MCP23017 pin.
//
// Obtained via (*MCP23017Minimal).Pin or (*MCP23017Full).Pin. The Pin
// holds a plain pointer back to the driver.
type MCP23017Pin struct {
	chip *MCP23017Minimal
	n    uint8
}

// Get reads the actual logic level at the pin via the GPIO register.
func (p MCP23017Pin) Get() (bool, error) {
	port := p.n >> 3
	bit := p.n & 7
	buf, err := p.chip.readReg(mcpRegGPIOA + port)
	if err != nil {
		return false, err
	}
	return ((buf >> bit) & 1) == 1, nil
}

// Set writes 1 (high) or 0 (low) to this pin via the output latch.
func (p MCP23017Pin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Toggle inverts the shadow bit for this pin.
func (p MCP23017Pin) Toggle() error {
	port := p.n >> 3
	bit := p.n & 7
	high := ((p.chip.shadow[port] >> bit) & 1) == 0
	return p.chip.setPin(p.n, high)
}

// MCP23017Full is the MCP23017 driver — full interface. Extends
// MCP23017Minimal with pull-up configuration, polarity inversion, and
// interrupt support.
type MCP23017Full struct {
	*MCP23017Minimal
	prev [2]uint8
}

// NewMCP23017Full creates a new MCP23017Full with the same mandatory
// initialisation as NewMCP23017Minimal.
func NewMCP23017Full(t transport.Transport, addr uint8) (*MCP23017Full, error) {
	m, err := NewMCP23017Minimal(t, addr)
	if err != nil {
		return nil, err
	}
	return &MCP23017Full{MCP23017Minimal: m}, nil
}

// Pin returns a Pin proxy for pin n (0–15) backed by this driver's
// underlying transport.
func (d *MCP23017Full) Pin(n uint8) MCP23017Pin {
	return d.MCP23017Minimal.Pin(n)
}

// ConfigurePullup enables or disables the 100 kΩ internal pull-up on
// each pin of the given port. Pull-ups are only electrically active on
// pins configured as inputs; enabling them on output pins has no
// hardware effect.
//
// mask bit n = 1 → pull-up enabled on pin n.
func (d *MCP23017Full) ConfigurePullup(port uint8, mask uint8) error {
	return d.MCP23017Minimal.writeReg(mcpRegGPPUA+(port&1), mask)
}

// ConfigurePolarity sets the input polarity inversion register for the
// given port.
//
// mask bit n = 1 → GPIO read for pin n is inverted.
func (d *MCP23017Full) ConfigurePolarity(port uint8, mask uint8) error {
	return d.MCP23017Minimal.writeReg(mcpRegIPOLA+(port&1), mask)
}

// ReadInterruptFlags reads the interrupt flag register for the given
// port (INTFA/INTFB) without clearing the interrupt. Bit n = 1 means
// pin n triggered an interrupt.
func (d *MCP23017Full) ReadInterruptFlags(port uint8) (uint8, error) {
	return d.MCP23017Minimal.readReg(mcpRegINTFA + (port & 1))
}

// ClearInterrupt reads INTCAP for the given port (clearing INT) and
// returns the 8-bit bitmask of pins whose level differs from the
// previous read. The previous-state tracker is updated.
func (d *MCP23017Full) ClearInterrupt(port uint8) (uint8, error) {
	p := port & 1
	if _, err := d.MCP23017Minimal.readReg(mcpRegINTCAPA + p); err != nil {
		return 0, err
	}
	current, err := d.MCP23017Minimal.readReg(mcpRegGPIOA + p)
	if err != nil {
		return 0, err
	}
	changed := current ^ d.prev[p]
	d.prev[p] = current
	return changed, nil
}

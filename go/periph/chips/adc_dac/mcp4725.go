// Package adcdac contains drivers for ADC and DAC converter chips.
//
// Subpackage files implement individual chip drivers (MCP4725, MCP4728,
// PCF8591, HX711).
package adcdac

import (
	"time"

	"github.com/tuhde/Periph/go/periph/transport"
)

// MCP4725 command bytes and General Call opcodes.
const (
	mcp4725AddrGeneralCall = 0x00

	mcp4725GCReset = 0x06
	mcp4725GCWake  = 0x09

	// Write DAC + EEPROM command: bits C2 C1 C0 = 0 1 1; PD1 PD0 in bits 2:1.
	mcp4725CmdWriteDACEeprom = 0x60
)

const mcp4725EepromWriteDelay = 50 * time.Millisecond

// MCP4725Minimal is the single-channel 12-bit DAC driver — minimal interface.
//
// Uses Fast Write (2-byte) to update the volatile DAC register only. EEPROM
// is unaffected. The default output mode is Normal (PD1=PD0=0).
type MCP4725Minimal struct {
	transport transport.Transport
}

// NewMCP4725Minimal creates a new MCP4725Minimal bound to the given transport.
// The chip needs no I²C traffic at construction; the EEPROM value is
// automatically loaded into the DAC register at power-up.
//
// transport must be a configured I²C transport bound to the chip's 7-bit
// address (typically 0x60, 0x61 depending on the A0 pin).
func NewMCP4725Minimal(t transport.Transport) (*MCP4725Minimal, error) {
	return &MCP4725Minimal{transport: t}, nil
}

// SetVoltage sets the DAC output as a fraction of V_DD.
//
// Clamps fraction to [0.0, 1.0] and uses Fast Write. The DAC register
// is updated; EEPROM is not.
func (d *MCP4725Minimal) SetVoltage(fraction float32) error {
	f := fraction
	if f < 0.0 {
		f = 0.0
	}
	if f > 1.0 {
		f = 1.0
	}
	code := uint16(f * 4095.0)
	if code > 4095 {
		code = 4095
	}
	return d.fastWrite(code, 0)
}

// SetRaw sets the raw 12-bit DAC code.
//
// Clamps code to [0, 4095] and uses Fast Write.
func (d *MCP4725Minimal) SetRaw(code uint16) error {
	if code > 4095 {
		code = 4095
	}
	return d.fastWrite(code, 0)
}

// fastWrite issues the 2-byte Fast Write command to update the DAC register
// without touching the EEPROM. PD bits are encoded in bits 5:4 of byte 1.
func (d *MCP4725Minimal) fastWrite(code uint16, pd uint8) error {
	buf := []byte{
		byte((pd&0x03)<<4) | byte((code>>8)&0x0F),
		byte(code & 0xFF),
	}
	return d.transport.Write(buf)
}

// MCP4725State is the read-back result for an MCP4725 device.
type MCP4725State struct {
	// Code is the current DAC register value (0–4095).
	Code uint16
	// VoltageFraction is Code / 4095.0.
	VoltageFraction float32
	// PowerDown is the current power-down mode (0–3).
	PowerDown uint8
	// EEPROMCode is the EEPROM-stored DAC code (0–4095).
	EEPROMCode uint16
	// EEPROMPowerDown is the EEPROM-stored power-down mode (0–3).
	EEPROMPowerDown uint8
	// EEPROMReady is true when any pending EEPROM write has completed.
	EEPROMReady bool
}

// MCP4725Full is the single-channel 12-bit DAC driver — full interface.
// Extends MCP4725Minimal with EEPROM writes, power-down, read-back, and
// General Call Reset / Wake-Up.
//
// Embeds MCP4725Minimal to inherit SetVoltage, SetRaw, and the constructor.
type MCP4725Full struct {
	*MCP4725Minimal
}

// NewMCP4725Full creates a new MCP4725Full bound to the given transport.
func NewMCP4725Full(t transport.Transport) (*MCP4725Full, error) {
	m, err := NewMCP4725Minimal(t)
	if err != nil {
		return nil, err
	}
	return &MCP4725Full{MCP4725Minimal: m}, nil
}

// SetVoltageEEPROM sets the DAC output and persists to EEPROM.
//
// Writes both the DAC register and EEPROM so the value survives power
// cycles. Waits the worst-case 50 ms for the EEPROM write to complete.
func (d *MCP4725Full) SetVoltageEEPROM(fraction float32) error {
	f := fraction
	if f < 0.0 {
		f = 0.0
	}
	if f > 1.0 {
		f = 1.0
	}
	code := uint16(f * 4095.0)
	if code > 4095 {
		code = 4095
	}
	return d.writeDACEeprom(code, 0)
}

// SetRawEEPROM sets the raw 12-bit code and persists to EEPROM.
func (d *MCP4725Full) SetRawEEPROM(code uint16) error {
	if code > 4095 {
		code = 4095
	}
	return d.writeDACEeprom(code, 0)
}

// Read returns the current DAC register, EEPROM contents, and status bits.
//
// Issues a 5-byte read. Byte 1 carries the RDY/BSY, POR, and current PD
// bits; bytes 2–3 are the DAC register code; bytes 4–5 are the EEPROM code
// and PD bits.
func (d *MCP4725Full) Read() (MCP4725State, error) {
	buf, err := d.transport.WriteRead([]byte{0x00}, 5)
	if err != nil {
		return MCP4725State{}, err
	}
	code := (uint16(buf[1]&0x0F) << 8) | uint16(buf[2])
	eepromCode := (uint16(buf[3]&0x0F) << 8) | uint16(buf[4])
	return MCP4725State{
		Code:            code,
		VoltageFraction: float32(code) / 4095.0,
		PowerDown:       (buf[0] >> 2) & 0x03,
		EEPROMCode:      eepromCode,
		EEPROMPowerDown: (buf[3] >> 6) & 0x03,
		EEPROMReady:     buf[0]&0x80 != 0,
	}, nil
}

// SetPowerDown sets the power-down mode while preserving the current DAC code.
//
// Mode 0 = normal, 1 = 1 kΩ to GND, 2 = 100 kΩ to GND, 3 = 500 kΩ to GND.
func (d *MCP4725Full) SetPowerDown(mode uint8) error {
	if mode > 3 {
		mode = 3
	}
	code, err := d.readDACCode()
	if err != nil {
		return err
	}
	return d.fastWrite(code, mode)
}

// WakeUp sends the General Call Wake-Up command (0x00, 0x09) to clear the
// power-down bits in the DAC register.
func (d *MCP4725Full) WakeUp() error {
	return d.transport.Write([]byte{mcp4725GCWake})
}

// Reset sends the General Call Reset command (0x00, 0x06) to trigger an
// internal power-on reset and reload the EEPROM into the DAC register.
func (d *MCP4725Full) Reset() error {
	return d.transport.Write([]byte{mcp4725GCReset})
}

// IsEEPROMReady returns true when any pending EEPROM write has completed.
//
// Reads the status byte and returns the RDY/BSY bit.
func (d *MCP4725Full) IsEEPROMReady() (bool, error) {
	buf, err := d.transport.WriteRead([]byte{0x00}, 1)
	if err != nil {
		return false, err
	}
	return buf[0]&0x80 != 0, nil
}

// writeDACEeprom issues the 3-byte Write DAC + EEPROM command and waits
// for the EEPROM write to complete.
func (d *MCP4725Full) writeDACEeprom(code uint16, pd uint8) error {
	buf := []byte{
		mcp4725CmdWriteDACEeprom | byte((pd&0x03)<<1),
		byte(code >> 4),
		byte((code & 0x0F) << 4),
	}
	if err := d.transport.Write(buf); err != nil {
		return err
	}
	time.Sleep(mcp4725EepromWriteDelay)
	return nil
}

// readDACCode reads the 2-byte DAC register.
func (d *MCP4725Full) readDACCode() (uint16, error) {
	buf, err := d.transport.WriteRead([]byte{0x00}, 2)
	if err != nil {
		return 0, err
	}
	return (uint16(buf[0]&0x0F) << 8) | uint16(buf[1]), nil
}

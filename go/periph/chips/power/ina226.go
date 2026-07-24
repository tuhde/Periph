// Package power contains drivers for Power management chips.
package power

import (
	"github.com/tuhde/Periph/go/periph/transport"
)

// INA226 register addresses.
const (
	ina226RegConfig   uint8 = 0x00
	ina226RegShunt    uint8 = 0x01
	ina226RegBus      uint8 = 0x02
	ina226RegPower    uint8 = 0x03
	ina226RegCurrent  uint8 = 0x04
	ina226RegCal      uint8 = 0x05
	ina226RegMask     uint8 = 0x06
	ina226RegAlert    uint8 = 0x07
	ina226RegMfrID    uint8 = 0xFE
	ina226RegDieID    uint8 = 0xFF
)

// INA226 power-on Configuration Register default: MODE=7, VBUSCT=4 (1.1 ms),
// VSHCT=4 (1.1 ms), AVG=0 (1 sample). The chip ships in this state; the
// Minimal driver writes it explicitly to make the configuration reproducible.
const ina226ConfigDefault uint16 = 0x4127

// Alert function constants — pass one of these to INA226Full.SetAlert.
const (
	// SOL is the alert-function bit for shunt voltage over-limit.
	SOL uint16 = 0x8000
	// SUL is the alert-function bit for shunt voltage under-limit.
	SUL uint16 = 0x4000
	// BOL is the alert-function bit for bus voltage over-limit.
	BOL uint16 = 0x2000
	// BUL is the alert-function bit for bus voltage under-limit.
	BUL uint16 = 0x1000
	// POL is the alert-function bit for power over-limit.
	POL uint16 = 0x0800
	// CNVR is the alert-function bit for conversion ready.
	CNVR uint16 = 0x0400
	// AFF is the Alert Function Flag bit, readable from INA226Full.AlertFlags.
	AFF uint16 = 0x0010
)

// INA226Minimal is the INA226 36V, 16-bit current/voltage/power monitor
// driver — minimal interface.
//
// Communicates over I²C at up to 400 kHz fast mode. Current and power
// readings require the Calibration Register to be programmed; the
// constructor computes and writes it automatically.
//
// Default configuration baked in at construction: MODE = 7 (shunt+bus
// continuous), VBUSCT = 4 (1.1 ms), VSHCT = 4 (1.1 ms), AVG = 0
// (1 sample, no averaging).
type INA226Minimal struct {
	transport   transport.Transport
	currentLSB  float32
	cal         uint16
}

// NewINA226Minimal creates a new INA226Minimal, programs the Configuration
// Register, and programs the Calibration Register from r_shunt and max_current.
//
// transport must be a configured I²C transport bound to the device's
// 7-bit address (0x40–0x4F, default 0x40).
//
// r_shunt is the shunt resistor value in ohms. max_current is the maximum
// expected current in amperes; it determines the current LSB used by
// Current() and Power().
func NewINA226Minimal(transport transport.Transport, rShunt float32, maxCurrent float32) (*INA226Minimal, error) {
	d := &INA226Minimal{
		transport:  transport,
		currentLSB: maxCurrent / 32768.0,
	}
	d.cal = uint16(0.00512 / (d.currentLSB * rShunt))
	if err := d.writeReg(ina226RegConfig, ina226ConfigDefault); err != nil {
		return nil, err
	}
	if err := d.writeReg(ina226RegCal, d.cal); err != nil {
		return nil, err
	}
	return d, nil
}

// writeReg writes a 16-bit value to a register (big-endian, pointer byte
// followed by two data bytes).
func (d *INA226Minimal) writeReg(reg uint8, value uint16) error {
	return d.transport.Write([]byte{reg, byte(value >> 8), byte(value & 0xFF)})
}

// readReg reads a 16-bit unsigned register value.
func (d *INA226Minimal) readReg(reg uint8) (uint16, error) {
	buf, err := d.transport.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0])<<8 | uint16(buf[1]), nil
}

// readRegSigned reads a 16-bit register value and returns it as signed.
func (d *INA226Minimal) readRegSigned(reg uint8) (int16, error) {
	v, err := d.readReg(reg)
	if err != nil {
		return 0, err
	}
	return int16(v), nil
}

// Voltage reads the bus voltage. LSB = 1.25 mV; the raw register is unsigned.
func (d *INA226Minimal) Voltage() (float32, error) {
	raw, err := d.readReg(ina226RegBus)
	if err != nil {
		return 0, err
	}
	return float32(raw) * 1.25e-3, nil
}

// ShuntVoltage reads the differential shunt voltage. LSB = 2.5 µV; the
// raw register is signed (2's complement).
func (d *INA226Minimal) ShuntVoltage() (float32, error) {
	raw, err := d.readRegSigned(ina226RegShunt)
	if err != nil {
		return 0, err
	}
	return float32(raw) * 2.5e-6, nil
}

// Current reads the calculated current through the shunt. Requires the
// Calibration Register to be programmed (done at construction). The raw
// Current register is signed.
func (d *INA226Minimal) Current() (float32, error) {
	raw, err := d.readRegSigned(ina226RegCurrent)
	if err != nil {
		return 0, err
	}
	return float32(raw) * d.currentLSB, nil
}

// Power reads the calculated power. Requires the Calibration Register
// to be programmed. Power_LSB = 25 × Current_LSB.
func (d *INA226Minimal) Power() (float32, error) {
	raw, err := d.readReg(ina226RegPower)
	if err != nil {
		return 0, err
	}
	return float32(raw) * 25.0 * d.currentLSB, nil
}

// INA226Full is the INA226 driver — full interface. Extends INA226Minimal
// with Configuration Register access, alert configuration, status flags,
// power management, and ID-register reads.
type INA226Full struct {
	*INA226Minimal
	mode uint8
}

// NewINA226Full creates a new INA226Full and programs the Calibration
// Register. Same arguments as NewINA226Minimal.
func NewINA226Full(transport transport.Transport, rShunt float32, maxCurrent float32) (*INA226Full, error) {
	m, err := NewINA226Minimal(transport, rShunt, maxCurrent)
	if err != nil {
		return nil, err
	}
	return &INA226Full{INA226Minimal: m, mode: 0x07}, nil
}

// Configure writes the Configuration Register.
//
// avg is the averaging count selector 0–7 (0 = 1 sample, 7 = 1024).
// vbusCT is the bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
// vshCT is the shunt voltage conversion time selector 0–7 (default 4 = 1.1 ms).
// mode is the operating mode 0–7 (default 7 = shunt+bus continuous).
func (d *INA226Full) Configure(avg, vbusCT, vshCT, mode uint8) error {
	config := (uint16(avg&0x07) << 9) |
		(uint16(vbusCT&0x07) << 6) |
		(uint16(vshCT&0x07) << 3) |
		uint16(mode&0x07)
	d.mode = mode & 0x07
	return d.writeReg(ina226RegConfig, config)
}

// ConversionReady reads the CVRF (Conversion Ready Flag) bit from the
// Mask/Enable Register. Note: reading Mask/Enable clears CVRF — read it
// last if also checking other flags.
func (d *INA226Full) ConversionReady() (bool, error) {
	v, err := d.readReg(ina226RegMask)
	if err != nil {
		return false, err
	}
	return v&0x0008 != 0, nil
}

// Overflow reads the OVF (Math Overflow Flag) bit from the Mask/Enable
// Register. Reading Mask/Enable clears the OVF bit when the AFF flag is
// set in latch mode; in transparent mode the OVF bit follows the
// underlying condition.
func (d *INA226Full) Overflow() (bool, error) {
	v, err := d.readReg(ina226RegMask)
	if err != nil {
		return false, err
	}
	return v&0x0004 != 0, nil
}

// SetAlert configures the alert pin function and threshold.
//
// Only one alert function can be active at a time. The limit is in
// natural units — volts for SOL/SUL/BOL/BUL, watts for POL. CNVR ignores
// the limit value.
//
// polarity false = active-low (default), true = active-high.
// latch false = transparent (default), true = latch until Mask/Enable is read.
func (d *INA226Full) SetAlert(function uint16, limit float32, polarity bool, latch bool) error {
	var raw uint16
	switch function {
	case SOL, SUL:
		raw = uint16(limit / 2.5e-6)
	case BOL, BUL:
		raw = uint16(limit / 1.25e-3)
	case POL:
		raw = uint16(limit / (25.0 * d.currentLSB))
	}
	mask := function
	if polarity {
		mask |= 0x0002
	}
	if latch {
		mask |= 0x0001
	}
	if err := d.writeReg(ina226RegMask, mask); err != nil {
		return err
	}
	return d.writeReg(ina226RegAlert, raw)
}

// AlertFlags reads the raw 16-bit Mask/Enable Register, which contains
// alert-enable bits, polarity/latch configuration, and the read-only
// status flags (AFF, CVRF, OVF). Reading the register clears the CVRF bit.
func (d *INA226Full) AlertFlags() (uint16, error) {
	return d.readReg(ina226RegMask)
}

// Reset sets the RST bit, restoring all registers to power-on defaults.
// The Calibration Register is re-written after the reset so current/power
// readings continue to work.
func (d *INA226Full) Reset() error {
	if err := d.writeReg(ina226RegConfig, 0x8000); err != nil {
		return err
	}
	return d.writeReg(ina226RegCal, d.cal)
}

// Shutdown enters power-down mode (MODE = 000), saving the current mode
// for Wake.
func (d *INA226Full) Shutdown() error {
	cfg, err := d.readReg(ina226RegConfig)
	if err != nil {
		return err
	}
	d.mode = uint8(cfg & 0x07)
	return d.writeReg(ina226RegConfig, cfg&0xFFF8)
}

// Wake restores the operating mode saved by Shutdown.
func (d *INA226Full) Wake() error {
	cfg, err := d.readReg(ina226RegConfig)
	if err != nil {
		return err
	}
	return d.writeReg(ina226RegConfig, (cfg&0xFFF8)|uint16(d.mode))
}

// ManufacturerID reads the Manufacturer ID Register. Expect 0x5449
// (Texas Instruments, ASCII "TI").
func (d *INA226Full) ManufacturerID() (uint16, error) {
	return d.readReg(ina226RegMfrID)
}

// DieID reads the Die ID Register. Expect 0x2260.
func (d *INA226Full) DieID() (uint16, error) {
	return d.readReg(ina226RegDieID)
}

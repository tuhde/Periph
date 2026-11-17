// Package power contains drivers for Power management chips.
package power

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// INA260 register addresses.
const (
	ina260RegConfig  uint8 = 0x00
	ina260RegCurrent uint8 = 0x01
	ina260RegBus     uint8 = 0x02
	ina260RegPower   uint8 = 0x03
	ina260RegMask    uint8 = 0x06
	ina260RegAlert   uint8 = 0x07
	ina260RegMfrID   uint8 = 0xFE
	ina260RegDieID   uint8 = 0xFF
)

// ina260ConfigBase is the Configuration Register base: bits 14:12 must be
// written as 110.
const ina260ConfigBase uint16 = 0x6000

// Alert function constants — pass one of these to INA260Full.SetAlert.
const (
	// INA260OCL is the alert-function bit for over-current limit.
	INA260OCL uint16 = 0x8000
	// INA260UCL is the alert-function bit for under-current limit.
	INA260UCL uint16 = 0x4000
	// INA260BOL is the alert-function bit for bus voltage over-limit.
	INA260BOL uint16 = 0x2000
	// INA260BUL is the alert-function bit for bus voltage under-limit.
	INA260BUL uint16 = 0x1000
	// INA260POL is the alert-function bit for power over-limit.
	INA260POL uint16 = 0x0800
	// INA260CNVR is the alert-function bit for conversion ready.
	INA260CNVR uint16 = 0x0400
	// INA260AFF is the Alert Function Flag bit, readable from INA260Full.AlertFlags.
	INA260AFF uint16 = 0x0010
)

// Averaging count selectors for INA260Full.Configure.
const (
	INA260Avg1    uint8 = 0
	INA260Avg4    uint8 = 1
	INA260Avg16   uint8 = 2
	INA260Avg64   uint8 = 3
	INA260Avg128  uint8 = 4
	INA260Avg256  uint8 = 5
	INA260Avg512  uint8 = 6
	INA260Avg1024 uint8 = 7
)

// Conversion time selectors for INA260Full.Configure (bus voltage and shunt current).
const (
	INA260CT140us  uint8 = 0
	INA260CT204us  uint8 = 1
	INA260CT332us  uint8 = 2
	INA260CT588us  uint8 = 3
	INA260CT1100us uint8 = 4
	INA260CT2116us uint8 = 5
	INA260CT4156us uint8 = 6
	INA260CT8244us uint8 = 7
)

// Operating modes for INA260Full.Configure and INA260Full.Trigger.
const (
	INA260ModePowerDown         uint8 = 0
	INA260ModeShuntTriggered    uint8 = 1
	INA260ModeBusTriggered      uint8 = 2
	INA260ModeShuntBusTriggered uint8 = 3
	INA260ModeShuntCont         uint8 = 5
	INA260ModeBusCont           uint8 = 6
	INA260ModeShuntBusCont      uint8 = 7
)

// INA260Minimal is the INA260 36V, 16-bit current/voltage/power monitor
// driver with integrated 2 mΩ shunt — minimal interface.
//
// Communicates over I²C at up to 400 kHz fast mode. The shunt is integrated
// and the Current, Bus Voltage and Power registers have fixed LSBs, so no
// calibration is needed and the constructor makes no bus access.
//
// Relies on the chip's power-on state: MODE = 7 (shunt+bus continuous),
// VBUSCT = 4 (1.1 ms), ISHCT = 4 (1.1 ms), AVG = 0 (1 sample).
type INA260Minimal struct {
	connection connection.RegisterConnection
}

// NewINA260Minimal creates a new INA260Minimal.
//
// connection must be a configured I²C connection bound to the device's
// 7-bit address (0x40–0x4F, default 0x40).
func NewINA260Minimal(connection connection.RegisterConnection) (*INA260Minimal, error) {
	return &INA260Minimal{connection: connection}, nil
}

// writeReg writes a 16-bit value to a register (big-endian, pointer byte
// followed by two data bytes).
func (d *INA260Minimal) writeReg(reg uint8, value uint16) error {
	return d.connection.WriteReg(uint32(reg), []byte{byte(value >> 8), byte(value & 0xFF)})
}

// readReg reads a 16-bit unsigned register value.
func (d *INA260Minimal) readReg(reg uint8) (uint16, error) {
	buf, err := d.connection.ReadReg(uint32(reg), 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0])<<8 | uint16(buf[1]), nil
}

// readRegSigned reads a 16-bit register value and returns it as signed.
func (d *INA260Minimal) readRegSigned(reg uint8) (int16, error) {
	v, err := d.readReg(reg)
	if err != nil {
		return 0, err
	}
	return int16(v), nil
}

// Voltage reads the bus voltage. LSB = 1.25 mV; the raw register is unsigned.
func (d *INA260Minimal) Voltage() (float32, error) {
	raw, err := d.readReg(ina260RegBus)
	if err != nil {
		return 0, err
	}
	return float32(raw) * 1.25e-3, nil
}

// Current reads the current through the integrated shunt in amperes.
// LSB = 1.25 mA; the raw register is signed (positive flows IN+ to IN-).
func (d *INA260Minimal) Current() (float32, error) {
	raw, err := d.readRegSigned(ina260RegCurrent)
	if err != nil {
		return 0, err
	}
	return float32(raw) * 1.25e-3, nil
}

// Power reads the calculated power in watts. LSB = 10 mW; always positive.
func (d *INA260Minimal) Power() (float32, error) {
	raw, err := d.readReg(ina260RegPower)
	if err != nil {
		return 0, err
	}
	return float32(raw) * 10e-3, nil
}

// INA260Full is the INA260 driver — full interface. Extends INA260Minimal
// with Configuration Register access, triggered conversions, alert
// configuration, status flags, power management, and ID-register reads.
type INA260Full struct {
	*INA260Minimal
	avg, vbusCT, ishCT, mode uint8
}

// NewINA260Full creates a new INA260Full. Makes no bus access.
func NewINA260Full(connection connection.RegisterConnection) (*INA260Full, error) {
	m, err := NewINA260Minimal(connection)
	if err != nil {
		return nil, err
	}
	return &INA260Full{INA260Minimal: m, avg: 0, vbusCT: 4, ishCT: 4, mode: 7}, nil
}

func (d *INA260Full) writeConfig(mode uint8) error {
	config := ina260ConfigBase |
		(uint16(d.avg) << 9) |
		(uint16(d.vbusCT) << 6) |
		(uint16(d.ishCT) << 3) |
		uint16(mode&0x07)
	return d.writeReg(ina260RegConfig, config)
}

// Configure writes the Configuration Register (bits 14:12 kept at 110).
//
// avg is the averaging count selector 0–7 (INA260Avg1 … INA260Avg1024).
// vbusCT is the bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
// ishCT is the shunt current conversion time selector 0–7 (default 4 = 1.1 ms).
// mode is the operating mode 0–7 (default 7 = shunt+bus continuous).
func (d *INA260Full) Configure(avg, vbusCT, ishCT, mode uint8) error {
	d.avg = avg & 0x07
	d.vbusCT = vbusCT & 0x07
	d.ishCT = ishCT & 0x07
	d.mode = mode & 0x07
	return d.writeConfig(d.mode)
}

// Trigger starts one triggered conversion with the current settings
// (rewrites the Configuration Register). Poll ConversionReady for completion.
// mode is INA260ModeShuntTriggered, INA260ModeBusTriggered or
// INA260ModeShuntBusTriggered.
func (d *INA260Full) Trigger(mode uint8) error {
	return d.writeConfig(mode)
}

// ConversionReady reads the CVRF (Conversion Ready Flag) bit from the
// Mask/Enable Register. Note: reading Mask/Enable clears CVRF — read it
// last if also checking other flags.
func (d *INA260Full) ConversionReady() (bool, error) {
	v, err := d.readReg(ina260RegMask)
	if err != nil {
		return false, err
	}
	return v&0x0008 != 0, nil
}

// Overflow reads the OVF (Math Overflow Flag) bit from the Mask/Enable
// Register.
func (d *INA260Full) Overflow() (bool, error) {
	v, err := d.readReg(ina260RegMask)
	if err != nil {
		return false, err
	}
	return v&0x0004 != 0, nil
}

// SetAlert configures the alert pin function and threshold.
//
// The Alert Limit register is written first, then Mask/Enable. Only one
// alert function can be active at a time. The limit is in natural units —
// amperes for OCL/UCL, volts for BOL/BUL, watts for POL. CNVR ignores the
// limit value and does not write the limit register.
//
// polarity false = active-low (default), true = active-high.
// latch false = transparent (default), true = latch until Mask/Enable is read.
func (d *INA260Full) SetAlert(function uint16, limit float32, polarity bool, latch bool) error {
	var scaled float32
	hasLimit := true
	switch function {
	case INA260OCL, INA260UCL, INA260BOL, INA260BUL:
		scaled = limit / 1.25e-3
	case INA260POL:
		scaled = limit / 10e-3
	default:
		hasLimit = false
	}
	if hasLimit {
		if scaled < 0 {
			scaled -= 0.5
		} else {
			scaled += 0.5
		}
		if err := d.writeReg(ina260RegAlert, uint16(int16(scaled))); err != nil {
			return err
		}
	}
	mask := function
	if polarity {
		mask |= 0x0002
	}
	if latch {
		mask |= 0x0001
	}
	return d.writeReg(ina260RegMask, mask)
}

// ClearAlert disables all alert functions (Mask/Enable = 0).
func (d *INA260Full) ClearAlert() error {
	return d.writeReg(ina260RegMask, 0)
}

// AlertFlags reads the raw 16-bit Mask/Enable Register, which contains
// alert-enable bits, polarity/latch configuration, and the read-only
// status flags (AFF, CVRF, OVF). Reading the register clears CVRF and, in
// latch mode, AFF.
func (d *INA260Full) AlertFlags() (uint16, error) {
	return d.readReg(ina260RegMask)
}

// Reset sets the RST bit, restoring all registers to power-on defaults.
func (d *INA260Full) Reset() error {
	if err := d.writeReg(ina260RegConfig, 0x8000); err != nil {
		return err
	}
	d.avg, d.vbusCT, d.ishCT, d.mode = 0, 4, 4, 7
	return nil
}

// Shutdown enters power-down mode (MODE = 000); the previous mode is kept
// for Wake.
func (d *INA260Full) Shutdown() error {
	return d.writeConfig(INA260ModePowerDown)
}

// Wake restores the operating mode that was active before Shutdown.
func (d *INA260Full) Wake() error {
	return d.writeConfig(d.mode)
}

// ManufacturerID reads the Manufacturer ID Register. Expect 0x5449
// (Texas Instruments, ASCII "TI").
func (d *INA260Full) ManufacturerID() (uint16, error) {
	return d.readReg(ina260RegMfrID)
}

// DieID reads the Die ID Register. (value >> 4) is 0x227; the low nibble
// is the revision.
func (d *INA260Full) DieID() (uint16, error) {
	return d.readReg(ina260RegDieID)
}

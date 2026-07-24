// Package power contains drivers for Power management chips.
package power

import (
	"github.com/tuhde/Periph/go/periph/transport"
)

// INA219 register addresses.
const (
	ina219RegConfig  uint8 = 0x00
	ina219RegShunt   uint8 = 0x01
	ina219RegBus     uint8 = 0x02
	ina219RegPower   uint8 = 0x03
	ina219RegCurrent uint8 = 0x04
	ina219RegCal     uint8 = 0x05
)

// INA219 minimal/full stage constants — PGA gain, bus range, ADC mode,
// and operating mode. Pass these to INA219Full.Configure.
const (
	// Bus-range selectors for INA219Full.Configure.
	BRNG_16V uint8 = 0
	BRNG_32V uint8 = 1

	// Shunt PGA gain selectors for INA219Full.Configure.
	PGA_1 uint8 = 0
	PGA_2 uint8 = 1
	PGA_4 uint8 = 2
	PGA_8 uint8 = 3

	// ADC resolution / averaging selectors for BADC and SADC in
	// INA219Full.Configure. The first four are single-shot resolutions
	// (9-, 10-, 11-, 12-bit); the rest are averaged sample counts.
	ADC_9BIT    uint8 = 0x00
	ADC_10BIT   uint8 = 0x01
	ADC_11BIT   uint8 = 0x02
	ADC_12BIT   uint8 = 0x03
	ADC_AVG_2   uint8 = 0x09
	ADC_AVG_4   uint8 = 0x0A
	ADC_AVG_8   uint8 = 0x0B
	ADC_AVG_16  uint8 = 0x0C
	ADC_AVG_32  uint8 = 0x0D
	ADC_AVG_64  uint8 = 0x0E
	ADC_AVG_128 uint8 = 0x0F

	// Operating-mode selectors for INA219Full.Configure.
	MODE_POWERDOWN       uint8 = 0
	MODE_SHUNT_TRIG      uint8 = 1
	MODE_BUS_TRIG        uint8 = 2
	MODE_SHUNT_BUS_TRIG  uint8 = 3
	MODE_ADC_OFF         uint8 = 4
	MODE_SHUNT_CONT      uint8 = 5
	MODE_BUS_CONT        uint8 = 6
	MODE_SHUNT_BUS_CONT  uint8 = 7
)

// INA219Minimal is the INA219 26V, 12-bit current/voltage/power monitor
// driver — minimal interface.
//
// Communicates over I²C at up to 400 kHz fast mode. Current and power
// readings require the Calibration Register to be programmed; the
// constructor computes and writes it automatically.
//
// The chip's power-on defaults are used (BRNG=1 / 32 V, PG=3 / ÷8,
// BADC=3 / 12-bit, SADC=3 / 12-bit, MODE=7 / shunt+bus continuous) —
// the Minimal driver does not rewrite the Configuration Register.
type INA219Minimal struct {
	transport  transport.Transport
	currentLSB float32
	cal        uint16
}

// NewINA219Minimal creates a new INA219Minimal and programs the
// Calibration Register from r_shunt and max_current.
//
// transport must be a configured I²C transport bound to the device's
// 7-bit address (0x40–0x4F, default 0x40).
//
// r_shunt is the shunt resistor value in ohms. max_current is the maximum
// expected current in amperes; it determines the current LSB.
func NewINA219Minimal(transport transport.Transport, rShunt float32, maxCurrent float32) (*INA219Minimal, error) {
	d := &INA219Minimal{
		transport:  transport,
		currentLSB: maxCurrent / 32768.0,
	}
	d.cal = uint16(0.04096/(d.currentLSB*rShunt)) & 0xFFFE
	if err := d.writeReg(ina219RegCal, d.cal); err != nil {
		return nil, err
	}
	return d, nil
}

// writeReg writes a 16-bit value to a register (big-endian, pointer byte
// followed by two data bytes).
func (d *INA219Minimal) writeReg(reg uint8, value uint16) error {
	return d.transport.Write([]byte{reg, byte(value >> 8), byte(value & 0xFF)})
}

// readReg reads a 16-bit unsigned register value.
func (d *INA219Minimal) readReg(reg uint8) (uint16, error) {
	buf, err := d.transport.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0])<<8 | uint16(buf[1]), nil
}

// readRegSigned reads a 16-bit register value and returns it as signed.
func (d *INA219Minimal) readRegSigned(reg uint8) (int16, error) {
	v, err := d.readReg(reg)
	if err != nil {
		return 0, err
	}
	return int16(v), nil
}

// Voltage reads the bus voltage. The Bus Voltage register holds the value
// left-shifted by 3 (bits 2:0 are reserved/CNVR/OVF), so the raw value is
// right-shifted before scaling by 4 mV.
func (d *INA219Minimal) Voltage() (float32, error) {
	raw, err := d.readReg(ina219RegBus)
	if err != nil {
		return 0, err
	}
	return float32(raw>>3) * 4e-3, nil
}

// ShuntVoltage reads the differential shunt voltage. LSB = 10 µV; the raw
// register is signed (2's complement).
func (d *INA219Minimal) ShuntVoltage() (float32, error) {
	raw, err := d.readRegSigned(ina219RegShunt)
	if err != nil {
		return 0, err
	}
	return float32(raw) * 10e-6, nil
}

// Current reads the calculated current through the shunt. Requires the
// Calibration Register to be programmed (done at construction). The raw
// Current register is signed.
func (d *INA219Minimal) Current() (float32, error) {
	raw, err := d.readRegSigned(ina219RegCurrent)
	if err != nil {
		return 0, err
	}
	return float32(raw) * d.currentLSB, nil
}

// Power reads the calculated power. Requires the Calibration Register
// to be programmed. Power_LSB = 20 × Current_LSB.
func (d *INA219Minimal) Power() (float32, error) {
	raw, err := d.readReg(ina219RegPower)
	if err != nil {
		return 0, err
	}
	return float32(raw) * 20.0 * d.currentLSB, nil
}

// INA219Full is the INA219 driver — full interface. Extends INA219Minimal
// with Configuration Register access, status flags, power management,
// and single-shot trigger.
type INA219Full struct {
	*INA219Minimal
	mode uint8
}

// NewINA219Full creates a new INA219Full and programs the Calibration
// Register. Same arguments as NewINA219Minimal.
func NewINA219Full(transport transport.Transport, rShunt float32, maxCurrent float32) (*INA219Full, error) {
	m, err := NewINA219Minimal(transport, rShunt, maxCurrent)
	if err != nil {
		return nil, err
	}
	return &INA219Full{INA219Minimal: m, mode: 0x07}, nil
}

// Configure writes the Configuration Register and re-writes the
// Calibration Register (matching the chip's behaviour after a Configuration
// Register write).
//
// brng is the bus voltage range (BRNG_16V / BRNG_32V).
// pga is the shunt PGA gain (PGA_1 / PGA_2 / PGA_4 / PGA_8).
// badc is the bus ADC resolution / averaging selector (0x00–0x0F).
// sadc is the shunt ADC resolution / averaging selector (0x00–0x0F).
// mode is the operating mode 0–7.
func (d *INA219Full) Configure(brng, pga, badc, sadc, mode uint8) error {
	config := (uint16(brng&1) << 13) |
		(uint16(pga&3) << 11) |
		(uint16(badc&0x0F) << 7) |
		(uint16(sadc&0x0F) << 3) |
		uint16(mode&7)
	d.mode = mode & 7
	if err := d.writeReg(ina219RegConfig, config); err != nil {
		return err
	}
	return d.writeReg(ina219RegCal, d.cal)
}

// ConversionReady reads the CNVR (Conversion Ready) flag bit from the
// Bus Voltage Register. The CNVR bit clears when the Power register is
// read or when the Configuration Register is written to an active mode.
func (d *INA219Full) ConversionReady() (bool, error) {
	v, err := d.readReg(ina219RegBus)
	if err != nil {
		return false, err
	}
	return v&0x02 != 0, nil
}

// Overflow reads the OVF (Math Overflow) flag bit from the Bus Voltage
// Register. Set when the current/power calculation overflows.
func (d *INA219Full) Overflow() (bool, error) {
	v, err := d.readReg(ina219RegBus)
	if err != nil {
		return false, err
	}
	return v&0x01 != 0, nil
}

// Reset sets the RST bit, restoring all registers to power-on defaults.
// The Calibration Register is re-written after the reset so current/power
// readings continue to work.
func (d *INA219Full) Reset() error {
	if err := d.writeReg(ina219RegConfig, 0x8000); err != nil {
		return err
	}
	return d.writeReg(ina219RegCal, d.cal)
}

// Shutdown enters power-down mode (MODE = 000), saving the current mode
// for Wake.
func (d *INA219Full) Shutdown() error {
	cfg, err := d.readReg(ina219RegConfig)
	if err != nil {
		return err
	}
	d.mode = uint8(cfg & 0x07)
	return d.writeReg(ina219RegConfig, cfg&0xFFF8)
}

// Wake restores the operating mode saved by Shutdown.
func (d *INA219Full) Wake() error {
	cfg, err := d.readReg(ina219RegConfig)
	if err != nil {
		return err
	}
	return d.writeReg(ina219RegConfig, (cfg&0xFFF8)|uint16(d.mode))
}

// Trigger re-writes the current Configuration Register to start a new
// single-shot conversion. Only effective when the current mode is a
// triggered mode (1, 2, or 3).
func (d *INA219Full) Trigger() error {
	cfg, err := d.readReg(ina219RegConfig)
	if err != nil {
		return err
	}
	return d.writeReg(ina219RegConfig, cfg)
}

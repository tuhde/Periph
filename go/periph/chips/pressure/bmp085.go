// Package pressure contains drivers for Standalone pressure sensors.
package pressure

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BMP085 register addresses.
const (
	bmp085RegID        uint8 = 0xD0
	bmp085RegCalStart  uint8 = 0xAA
	bmp085RegSoftReset uint8 = 0xE0
	bmp085RegCtrlMeas  uint8 = 0xF4
	bmp085RegOutMsb    uint8 = 0xF6
)

// BMP085 measurement command bytes.
const (
	bmp085CmdTemp      uint8 = 0x2E
	bmp085CmdPressOss0 uint8 = 0x34
	bmp085CmdPressOss1 uint8 = 0x74
	bmp085CmdPressOss2 uint8 = 0xB4
	bmp085CmdPressOss3 uint8 = 0xF4
)

// BMP085 expected chip ID.
const bmp085ChipID uint8 = 0x55

// BMP085 soft-reset command byte.
const bmp085SoftResetCmd uint8 = 0xB6

// Conversion times (microseconds) per the datasheet.
const (
	bmp085ConvTimeTempUs = 4500
	bmp085ConvTimeOss0Us = 4500
	bmp085ConvTimeOss1Us = 7500
	bmp085ConvTimeOss2Us = 13500
	bmp085ConvTimeOss3Us = 25500
)

// Bmp085Addr is the fixed 7-bit I²C address of the chip.
const Bmp085Addr uint8 = 0x77

// Oversampling mode constants are shared with BMP180 (OssUlp, OssStandard,
// OssHighRes, OssUltraHighRes in bmp180.go) — same encoding, same chip family.

// Bmp085Minimal is the digital barometric pressure and temperature sensor
// driver — minimal interface.
//
// Reads temperature and pressure via I²C using Bosch's integer compensation
// algorithm. Calibration coefficients are loaded from the chip's EEPROM
// during construction and sanity-checked. The chip ID register is verified
// to be 0x55.
//
// Default oversampling setting (OSS): 0 (ultra-low-power).
type Bmp085Minimal struct {
	connection connection.Connection

	// Calibration coefficients (signed/unsigned per the datasheet).
	ac1, ac2, ac3 int32
	ac4, ac5, ac6 uint32
	b1, b2        int32
	mb, mc, md    int32

	oss uint8
	b5  int32
}

// NewBmp085Minimal creates a new Bmp085Minimal, verifies the chip ID, and
// loads the calibration coefficients.
//
// connection must be a configured I²C connection bound to address 0x77.
func NewBmp085Minimal(t connection.Connection) (*Bmp085Minimal, error) {
	d := &Bmp085Minimal{connection: t, oss: 0}

	id, err := d.readReg8(bmp085RegID)
	if err != nil {
		return nil, err
	}
	if id != bmp085ChipID {
		return nil, fmt.Errorf("bmp085: chip ID mismatch: expected 0x55, got 0x%02X", id)
	}
	if err := d.readCalibration(); err != nil {
		return nil, err
	}
	return d, nil
}

// readReg8 reads a single byte from the given register.
func (d *Bmp085Minimal) readReg8(reg uint8) (uint8, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// readReg16 reads a 16-bit big-endian value from the given register.
func (d *Bmp085Minimal) readReg16(reg uint8) (uint16, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return (uint16(buf[0]) << 8) | uint16(buf[1]), nil
}

// writeReg8 writes a single byte to the given register.
func (d *Bmp085Minimal) writeReg8(reg uint8, val uint8) error {
	return d.connection.Write([]byte{reg, val})
}

// readCalibration reads and unpacks the 22-byte calibration block from EEPROM
// (0xAA-0xBF) and sanity-checks that no coefficient is 0x0000 or 0xFFFF.
func (d *Bmp085Minimal) readCalibration() error {
	buf, err := d.connection.WriteRead([]byte{bmp085RegCalStart}, 22)
	if err != nil {
		return err
	}
	d.ac1 = int32(int16(uint16(buf[0])<<8 | uint16(buf[1])))
	d.ac2 = int32(int16(uint16(buf[2])<<8 | uint16(buf[3])))
	d.ac3 = int32(int16(uint16(buf[4])<<8 | uint16(buf[5])))
	d.ac4 = uint32(buf[6])<<8 | uint32(buf[7])
	d.ac5 = uint32(buf[8])<<8 | uint32(buf[9])
	d.ac6 = uint32(buf[10])<<8 | uint32(buf[11])
	d.b1 = int32(int16(uint16(buf[12])<<8 | uint16(buf[13])))
	d.b2 = int32(int16(uint16(buf[14])<<8 | uint16(buf[15])))
	d.mb = int32(int16(uint16(buf[16])<<8 | uint16(buf[17])))
	d.mc = int32(int16(uint16(buf[18])<<8 | uint16(buf[19])))
	d.md = int32(int16(uint16(buf[20])<<8 | uint16(buf[21])))

	coeffs := []struct {
		name string
		v    uint32
	}{
		{"AC1", uint32(uint16(d.ac1))}, {"AC2", uint32(uint16(d.ac2))}, {"AC3", uint32(uint16(d.ac3))},
		{"AC4", d.ac4}, {"AC5", d.ac5}, {"AC6", d.ac6},
		{"B1", uint32(uint16(d.b1))}, {"B2", uint32(uint16(d.b2))},
		{"MB", uint32(uint16(d.mb))}, {"MC", uint32(uint16(d.mc))}, {"MD", uint32(uint16(d.md))},
	}
	for _, c := range coeffs {
		if c.v == 0x0000 || c.v == 0xFFFF {
			return fmt.Errorf("bmp085: invalid calibration: %s = 0x%04X", c.name, c.v)
		}
	}
	return nil
}

// readRawTemperature triggers a temperature conversion, waits the conversion
// time, and returns the raw UT value.
func (d *Bmp085Minimal) readRawTemperature() (int32, error) {
	if err := d.writeReg8(bmp085RegCtrlMeas, bmp085CmdTemp); err != nil {
		return 0, err
	}
	time.Sleep(bmp085ConvTimeTempUs * time.Microsecond)
	ut, err := d.readReg16(bmp085RegOutMsb)
	if err != nil {
		return 0, err
	}
	return int32(ut), nil
}

// readRawPressure triggers a pressure conversion at the active OSS, waits
// the conversion time, and returns the raw UP value (right-shifted by oss).
func (d *Bmp085Minimal) readRawPressure() (int32, error) {
	cmd := [4]uint8{bmp085CmdPressOss0, bmp085CmdPressOss1, bmp085CmdPressOss2, bmp085CmdPressOss3}[d.oss]
	convUs := [4]int{bmp085ConvTimeOss0Us, bmp085ConvTimeOss1Us, bmp085ConvTimeOss2Us, bmp085ConvTimeOss3Us}[d.oss]
	if err := d.writeReg8(bmp085RegCtrlMeas, cmd); err != nil {
		return 0, err
	}
	time.Sleep(time.Duration(convUs) * time.Microsecond)

	buf, err := d.connection.WriteRead([]byte{bmp085RegOutMsb}, 3)
	if err != nil {
		return 0, err
	}
	raw := uint32(buf[0])<<16 | uint32(buf[1])<<8 | uint32(buf[2])
	raw >>= uint(8 - d.oss)
	return int32(raw), nil
}

// computeB5 computes B5 from a raw temperature value. Shared intermediate
// used by both temperature and pressure compensation.
func (d *Bmp085Minimal) computeB5(ut int32) int32 {
	x1 := ((ut - int32(d.ac6)) * int32(d.ac5)) >> 15
	x2 := (d.mc << 11) / (x1 + d.md)
	return x1 + x2
}

// Temperature reads the calibrated temperature in degrees Celsius.
func (d *Bmp085Minimal) Temperature() (float64, error) {
	ut, err := d.readRawTemperature()
	if err != nil {
		return 0, err
	}
	d.b5 = d.computeB5(ut)
	return float64((d.b5+8)>>4) / 10.0, nil
}

// Pressure reads the calibrated pressure in pascals.
//
// Re-reads temperature internally to refresh B5, then triggers a pressure
// measurement. Self-contained — may be called without a prior Temperature()
// call.
func (d *Bmp085Minimal) Pressure() (float64, error) {
	ut, err := d.readRawTemperature()
	if err != nil {
		return 0, err
	}
	d.b5 = d.computeB5(ut)
	up, err := d.readRawPressure()
	if err != nil {
		return 0, err
	}

	b6 := d.b5 - 4000
	x1 := (d.b2 * ((b6 * b6) >> 12)) >> 11
	x2 := (d.ac2 * b6) >> 11
	x3 := x1 + x2
	b3 := (((d.ac1*4 + x3) << d.oss) + 2) >> 2

	x1 = (d.ac3 * b6) >> 13
	x2 = (d.b1 * ((b6 * b6) >> 12)) >> 16
	x3 = ((x1 + x2) + 2) >> 2
	b4 := (d.ac4 * uint32(x3+32768)) >> 15

	b7 := (uint32(up) - uint32(b3)) * (50000 >> d.oss)
	var p uint32
	if b7 < 0x80000000 {
		p = (b7 * 2) / b4
	} else {
		p = (b7 / b4) * 2
	}

	px1 := (p >> 8) * (p >> 8)
	px1 = (px1 * 3038) >> 16
	px2 := (-7357 * int32(p)) >> 16
	p = p + uint32((int32(px1)+px2+3791)>>4)

	return float64(p), nil
}

// Bmp085Full is the digital barometric pressure and temperature sensor
// driver — full interface. Extends Bmp085Minimal with oversampling control,
// altitude computation, sea-level pressure derivation, chip ID read-back,
// and soft reset.
//
// Embeds Bmp085Minimal to inherit Temperature, Pressure, and the
// constructor.
type Bmp085Full struct {
	*Bmp085Minimal
}

// NewBmp085Full creates a new Bmp085Full, verifies the chip ID, and loads
// the calibration coefficients.
func NewBmp085Full(t connection.Connection) (*Bmp085Full, error) {
	m, err := NewBmp085Minimal(t)
	if err != nil {
		return nil, err
	}
	return &Bmp085Full{Bmp085Minimal: m}, nil
}

// Oversampling returns the current oversampling setting (0–3).
func (d *Bmp085Full) Oversampling() uint8 {
	return d.oss
}

// SetOversampling changes the oversampling mode for subsequent Pressure() calls.
//
// oss: 0–3; use the OSS_* constants.
func (d *Bmp085Full) SetOversampling(oss uint8) {
	if oss > 3 {
		oss = 3
	}
	d.oss = oss
}

// Altitude computes altitude above sea level using the default reference
// pressure of 101325.0 Pa.
func (d *Bmp085Full) Altitude() (float64, error) {
	return d.AltitudeAt(101325.0)
}

// AltitudeAt computes altitude above sea level from the current pressure and
// a given sea-level reference.
//
// Uses the barometric formula: 44330 * (1 - (p / seaLevelPa)^(1/5.255))
func (d *Bmp085Full) AltitudeAt(seaLevelPa float64) (float64, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	return 44330.0 * (1.0 - pow(p/seaLevelPa, 1.0/5.255)), nil
}

// SeaLevelPressure back-calculates the sea-level pressure from the current
// reading and a known altitude.
func (d *Bmp085Full) SeaLevelPressure(altitudeM float64) (float64, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	return p / pow(1.0-altitudeM/44330.0, 5.255), nil
}

// ChipID reads the chip ID register (0xD0). Expected value is 0x55.
func (d *Bmp085Full) ChipID() (uint8, error) {
	return d.readReg8(bmp085RegID)
}

// Reset performs a soft reset and reloads the calibration coefficients.
func (d *Bmp085Full) Reset() error {
	if err := d.writeReg8(bmp085RegSoftReset, bmp085SoftResetCmd); err != nil {
		return err
	}
	time.Sleep(10 * time.Millisecond)
	return d.readCalibration()
}
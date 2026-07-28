// Package pressure contains drivers for Standalone pressure sensors.
package pressure

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BMP180 register addresses.
const (
	bmp180RegID        uint8 = 0xD0
	bmp180RegCalStart  uint8 = 0xAA
	bmp180RegSoftReset uint8 = 0xE0
	bmp180RegCtrlMeas  uint8 = 0xF4
	bmp180RegOutMsb    uint8 = 0xF6
)

// BMP180 measurement command bytes.
const (
	bmp180CmdTemp      uint8 = 0x2E
	bmp180CmdPressOss0 uint8 = 0x34
	bmp180CmdPressOss1 uint8 = 0x74
	bmp180CmdPressOss2 uint8 = 0xB4
	bmp180CmdPressOss3 uint8 = 0xF4
)

// BMP180 expected chip ID.
const bmp180ChipID uint8 = 0x55

// BMP180 soft-reset command byte.
const bmp180SoftResetCmd uint8 = 0xB6

// Conversion times (microseconds) per the datasheet.
const (
	bmp180ConvTimeTempUs = 4500
	bmp180ConvTimeOss0Us = 4500
	bmp180ConvTimeOss1Us = 7500
	bmp180ConvTimeOss2Us = 13500
	bmp180ConvTimeOss3Us = 25500
)

// Bmp180Addr is the fixed 7-bit I²C address of the chip.
const Bmp180Addr uint8 = 0x77

// Oversampling mode constants.
const (
	OssUlp           uint8 = 0
	OssStandard      uint8 = 1
	OssHighRes       uint8 = 2
	OssUltraHighRes  uint8 = 3
)

// Bmp180Minimal is the digital barometric pressure and temperature sensor
// driver — minimal interface.
//
// Reads temperature and pressure via I²C using Bosch's integer compensation
// algorithm. Calibration coefficients are loaded from the chip's EEPROM
// during construction and sanity-checked. The chip ID register is verified
// to be 0x55.
//
// Default oversampling setting (OSS): 0 (ultra-low-power).
type Bmp180Minimal struct {
	connection connection.Connection

	// Calibration coefficients (signed/unsigned per the datasheet).
	ac1, ac2, ac3 int32
	ac4, ac5, ac6 uint32
	b1, b2        int32
	mb, mc, md    int32

	oss uint8
	b5  int32
}

// NewBmp180Minimal creates a new Bmp180Minimal, verifies the chip ID, and
// loads the calibration coefficients.
//
// connection must be a configured I²C connection bound to address 0x77.
func NewBmp180Minimal(t connection.Connection) (*Bmp180Minimal, error) {
	d := &Bmp180Minimal{connection: t, oss: 0}

	id, err := d.readReg8(bmp180RegID)
	if err != nil {
		return nil, err
	}
	if id != bmp180ChipID {
		return nil, fmt.Errorf("bmp180: chip ID mismatch: expected 0x55, got 0x%02X", id)
	}
	if err := d.readCalibration(); err != nil {
		return nil, err
	}
	return d, nil
}

// readReg8 reads a single byte from the given register.
func (d *Bmp180Minimal) readReg8(reg uint8) (uint8, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// readReg16 reads a 16-bit big-endian value from the given register.
func (d *Bmp180Minimal) readReg16(reg uint8) (uint16, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return (uint16(buf[0]) << 8) | uint16(buf[1]), nil
}

// writeReg8 writes a single byte to the given register.
func (d *Bmp180Minimal) writeReg8(reg uint8, val uint8) error {
	return d.connection.Write([]byte{reg, val})
}

// readCalibration reads and unpacks the 22-byte calibration block from EEPROM
// (0xAA-0xBF) and sanity-checks that no coefficient is 0x0000 or 0xFFFF.
func (d *Bmp180Minimal) readCalibration() error {
	buf, err := d.connection.WriteRead([]byte{bmp180RegCalStart}, 22)
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
			return fmt.Errorf("bmp180: invalid calibration: %s = 0x%04X", c.name, c.v)
		}
	}
	return nil
}

// readRawTemperature triggers a temperature conversion, waits the conversion
// time, and returns the raw UT value.
func (d *Bmp180Minimal) readRawTemperature() (int32, error) {
	if err := d.writeReg8(bmp180RegCtrlMeas, bmp180CmdTemp); err != nil {
		return 0, err
	}
	time.Sleep(bmp180ConvTimeTempUs * time.Microsecond)
	ut, err := d.readReg16(bmp180RegOutMsb)
	if err != nil {
		return 0, err
	}
	return int32(ut), nil
}

// readRawPressure triggers a pressure conversion at the active OSS, waits
// the conversion time, and returns the raw UP value (right-shifted by oss).
func (d *Bmp180Minimal) readRawPressure() (int32, error) {
	cmd := [4]uint8{bmp180CmdPressOss0, bmp180CmdPressOss1, bmp180CmdPressOss2, bmp180CmdPressOss3}[d.oss]
	convUs := [4]int{bmp180ConvTimeOss0Us, bmp180ConvTimeOss1Us, bmp180ConvTimeOss2Us, bmp180ConvTimeOss3Us}[d.oss]
	if err := d.writeReg8(bmp180RegCtrlMeas, cmd); err != nil {
		return 0, err
	}
	time.Sleep(time.Duration(convUs) * time.Microsecond)

	buf, err := d.connection.WriteRead([]byte{bmp180RegOutMsb}, 3)
	if err != nil {
		return 0, err
	}
	raw := uint32(buf[0])<<16 | uint32(buf[1])<<8 | uint32(buf[2])
	raw >>= uint(8 - d.oss)
	return int32(raw), nil
}

// computeB5 computes B5 from a raw temperature value. Shared intermediate
// used by both temperature and pressure compensation.
func (d *Bmp180Minimal) computeB5(ut int32) int32 {
	x1 := ((ut - int32(d.ac6)) * int32(d.ac5)) >> 15
	x2 := (d.mc << 11) / (x1 + d.md)
	return x1 + x2
}

// Temperature reads the calibrated temperature in degrees Celsius.
func (d *Bmp180Minimal) Temperature() (float64, error) {
	ut, err := d.readRawTemperature()
	if err != nil {
		return 0, err
	}
	d.b5 = d.computeB5(ut)
	return float64((d.b5+8)>>4) / 10.0, nil
}

// Pressure reads the calibrated pressure in hPa.
//
// Re-reads temperature internally to refresh B5, then triggers a pressure
// measurement. Self-contained — may be called without a prior Temperature()
// call.
func (d *Bmp180Minimal) Pressure() (float64, error) {
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

	return float64(p) / 100.0, nil
}

// Bmp180Full is the digital barometric pressure and temperature sensor
// driver — full interface. Extends Bmp180Minimal with oversampling control,
// altitude computation, sea-level pressure derivation, chip ID read-back,
// and soft reset.
//
// Embeds Bmp180Minimal to inherit Temperature, Pressure, and the
// constructor.
type Bmp180Full struct {
	*Bmp180Minimal
}

// NewBmp180Full creates a new Bmp180Full, verifies the chip ID, and loads
// the calibration coefficients.
func NewBmp180Full(t connection.Connection) (*Bmp180Full, error) {
	m, err := NewBmp180Minimal(t)
	if err != nil {
		return nil, err
	}
	return &Bmp180Full{Bmp180Minimal: m}, nil
}

// Oversampling returns the current oversampling setting (0–3).
func (d *Bmp180Full) Oversampling() uint8 {
	return d.oss
}

// SetOversampling changes the oversampling mode for subsequent Pressure() calls.
//
// oss: 0–3; use the OSS_* constants.
func (d *Bmp180Full) SetOversampling(oss uint8) {
	if oss > 3 {
		oss = 3
	}
	d.oss = oss
}

// Altitude computes altitude above sea level using the default reference
// pressure of 1013.25 hPa.
func (d *Bmp180Full) Altitude() (float64, error) {
	return d.AltitudeAt(1013.25)
}

// AltitudeAt computes altitude above sea level from the current pressure and
// a given sea-level reference.
//
// Uses the barometric formula: 44330 * (1 - (p / seaLevelHpa)^(1/5.255))
func (d *Bmp180Full) AltitudeAt(seaLevelHpa float64) (float64, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	return 44330.0 * (1.0 - pow(p/seaLevelHpa, 1.0/5.255)), nil
}

// SeaLevelPressure back-calculates the sea-level pressure from the current
// reading and a known altitude.
func (d *Bmp180Full) SeaLevelPressure(altitudeM float64) (float64, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	return p / pow(1.0-altitudeM/44330.0, 5.255), nil
}

// ChipID reads the chip ID register (0xD0). Expected value is 0x55.
func (d *Bmp180Full) ChipID() (uint8, error) {
	return d.readReg8(bmp180RegID)
}

// Reset performs a soft reset and reloads the calibration coefficients.
func (d *Bmp180Full) Reset() error {
	if err := d.writeReg8(bmp180RegSoftReset, bmp180SoftResetCmd); err != nil {
		return err
	}
	time.Sleep(10 * time.Millisecond)
	return d.readCalibration()
}

// pow is a small integer/float power helper. TinyGo does not expose math.Pow
// on every board, and we use the formula only with a constant exponent in
// practice — but the call sites pass literal exponents, so this works as
// a thin wrapper.
func pow(x, y float64) float64 {
	return powImpl(x, y)
}

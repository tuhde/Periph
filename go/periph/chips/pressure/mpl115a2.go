package pressure

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MPL115A2 register addresses.
const (
	mpl115a2RegPadc    uint8 = 0x00
	mpl115a2RegCoef    uint8 = 0x04
	mpl115a2RegConvert uint8 = 0x12
)

// Mpl115a2Addr is the fixed 7-bit I²C address of the chip.
const Mpl115a2Addr uint8 = 0x60

const (
	mpl115a2ConversionTime = 5 * time.Millisecond
	mpl115a2TempCountsPerC = -5.35
	mpl115a2TempRefCounts  = 498.0
	mpl115a2TempRefC       = 25.0
)

// Mpl115a2Minimal is the NXP MPL115A2 miniature I²C digital barometer
// (50–115 kPa) driver — minimal interface.
//
// It reads the compensated absolute pressure in pascals and an approximate
// temperature in degrees Celsius. The four per-device compensation
// coefficients (a0, b1, b2, c12) are read once at construction and cached;
// each reading writes CONVERT, waits 5 ms and reads the ADC registers.
type Mpl115a2Minimal struct {
	connection connection.RegisterConnection

	a0, b1, b2, c12 float64
}

// NewMpl115a2Minimal creates the driver and reads the coefficient block.
//
// connection must be a configured I²C connection bound to address 0x60.
func NewMpl115a2Minimal(t connection.RegisterConnection) (*Mpl115a2Minimal, error) {
	d := &Mpl115a2Minimal{connection: t}
	if err := d.loadCoefficients(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *Mpl115a2Minimal) loadCoefficients() error {
	buf, err := d.connection.ReadReg(uint32(mpl115a2RegCoef), 8)
	if err != nil {
		return err
	}
	d.a0 = float64(int16(uint16(buf[0])<<8|uint16(buf[1]))) / 8.0
	d.b1 = float64(int16(uint16(buf[2])<<8|uint16(buf[3]))) / 8192.0
	d.b2 = float64(int16(uint16(buf[4])<<8|uint16(buf[5]))) / 16384.0
	d.c12 = float64(int16(uint16(buf[6])<<8|uint16(buf[7]))) / 16777216.0
	return nil
}

func (d *Mpl115a2Minimal) convert() (padc, tadc uint16, err error) {
	if err = d.connection.WriteReg(uint32(mpl115a2RegConvert), []byte{0x00}); err != nil {
		return
	}
	time.Sleep(mpl115a2ConversionTime)
	buf, err := d.connection.ReadReg(uint32(mpl115a2RegPadc), 4)
	if err != nil {
		return
	}
	padc = (uint16(buf[0])<<8 | uint16(buf[1])) >> 6
	tadc = (uint16(buf[2])<<8 | uint16(buf[3])) >> 6
	return
}

func (d *Mpl115a2Minimal) compensate(padc, tadc uint16) float64 {
	p, t := float64(padc), float64(tadc)
	pcomp := d.a0 + (d.b1+d.c12*t)*p + d.b2*t
	return (pcomp*(115.0-50.0)/1023.0 + 50.0) * 1000.0
}

func mpl115a2TemperatureC(tadc uint16) float64 {
	return mpl115a2TempRefC + (float64(tadc)-mpl115a2TempRefCounts)/mpl115a2TempCountsPerC
}

// ReadPressure runs one conversion and returns the compensated pressure in
// pascals.
func (d *Mpl115a2Minimal) ReadPressure() (float64, error) {
	padc, tadc, err := d.convert()
	if err != nil {
		return 0, err
	}
	return d.compensate(padc, tadc), nil
}

// ReadTemperature runs one conversion and returns an approximate temperature
// in degrees Celsius (25 + (Tadc − 498) / −5.35). The datasheet gives no
// formula; the value is accurate to a few degrees — use it for trends only.
func (d *Mpl115a2Minimal) ReadTemperature() (float64, error) {
	_, tadc, err := d.convert()
	if err != nil {
		return 0, err
	}
	return mpl115a2TemperatureC(tadc), nil
}

// Mpl115a2Full is the MPL115A2 driver — full interface. It extends
// Mpl115a2Minimal with a combined reading, raw ADC counts, coefficient
// access, the pure compensation function, hPa and altitude helpers.
type Mpl115a2Full struct {
	*Mpl115a2Minimal
}

// NewMpl115a2Full creates the driver and reads the coefficient block.
func NewMpl115a2Full(t connection.RegisterConnection) (*Mpl115a2Full, error) {
	m, err := NewMpl115a2Minimal(t)
	if err != nil {
		return nil, err
	}
	return &Mpl115a2Full{Mpl115a2Minimal: m}, nil
}

// Read runs one conversion and returns the pressure in pascals and the
// approximate temperature in degrees Celsius.
func (d *Mpl115a2Full) Read() (pressurePa, temperatureC float64, err error) {
	padc, tadc, err := d.convert()
	if err != nil {
		return 0, 0, err
	}
	return d.compensate(padc, tadc), mpl115a2TemperatureC(tadc), nil
}

// ReadRaw runs one conversion and returns the raw 10-bit ADC counts.
func (d *Mpl115a2Full) ReadRaw() (padc, tadc uint16, err error) {
	return d.convert()
}

// Coefficients returns the cached compensation coefficients a0, b1, b2, c12.
func (d *Mpl115a2Full) Coefficients() (a0, b1, b2, c12 float64) {
	return d.a0, d.b1, d.b2, d.c12
}

// ReloadCoefficients re-reads the coefficient block (e.g. after a power
// cycle).
func (d *Mpl115a2Full) ReloadCoefficients() error {
	return d.loadCoefficients()
}

// Compensate computes the pressure in pascals from raw counts and the cached
// coefficients (no bus access).
func (d *Mpl115a2Full) Compensate(padc, tadc uint16) float64 {
	return d.compensate(padc, tadc)
}

// PressureHpa runs one conversion and returns the pressure in hectopascals.
func (d *Mpl115a2Full) PressureHpa() (float64, error) {
	p, err := d.ReadPressure()
	if err != nil {
		return 0, err
	}
	return p / 100.0, nil
}

// Altitude computes the altitude in metres from a fresh pressure reading
// (barometric formula, informative). seaLevelPa is typically 101325.
func (d *Mpl115a2Full) Altitude(seaLevelPa float64) (float64, error) {
	p, err := d.ReadPressure()
	if err != nil {
		return 0, err
	}
	return 44330.0 * (1.0 - pow(p/seaLevelPa, 1.0/5.255)), nil
}

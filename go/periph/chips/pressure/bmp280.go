// Package pressure contains drivers for standalone pressure sensors.
package pressure

import (
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/transport"
)

// BMP280 register addresses.
const (
	bmp280RegCalStart uint8 = 0x88
	bmp280RegID       uint8 = 0xD0
	bmp280RegReset    uint8 = 0xE0
	bmp280RegStatus   uint8 = 0xF3
	bmp280RegCtrlMeas uint8 = 0xF4
	bmp280RegConfig   uint8 = 0xF5
	bmp280RegData     uint8 = 0xF7
)

// BMP280 expected chip ID.
const bmp280ChipID uint8 = 0x58

// BMP280 soft-reset command byte.
const bmp280ResetCmd uint8 = 0xB6

// BMP280 forced-mode measurement time (ultra-low-power preset, 6.4 ms max).
const bmp280MeasTime = 8 * time.Millisecond

// BMP280 oversampling modes.
const (
	// BMP280OSRSSkip disables the corresponding channel.
	BMP280OSRSSkip uint8 = 0
	// BMP280OSRSX1 is ×1 oversampling.
	BMP280OSRSX1 uint8 = 1
	// BMP280OSRSX2 is ×2 oversampling.
	BMP280OSRSX2 uint8 = 2
	// BMP280OSRSX4 is ×4 oversampling.
	BMP280OSRSX4 uint8 = 3
	// BMP280OSRSX8 is ×8 oversampling.
	BMP280OSRSX8 uint8 = 4
	// BMP280OSRSX16 is ×16 oversampling.
	BMP280OSRSX16 uint8 = 5
)

// BMP280 power modes.
const (
	// BMP280ModeSleep is the lowest-power mode; the chip idles.
	BMP280ModeSleep uint8 = 0
	// BMP280ModeForced triggers a single-shot measurement and returns to sleep.
	BMP280ModeForced uint8 = 1
	// BMP280ModeNormal makes the chip cycle continuously between standby and measurement.
	BMP280ModeNormal uint8 = 3
)

// BMP280 IIR filter coefficients.
const (
	// BMP280FilterOff disables the IIR filter.
	BMP280FilterOff uint8 = 0
	// BMP280Filter2 selects IIR coefficient 2.
	BMP280Filter2 uint8 = 1
	// BMP280Filter4 selects IIR coefficient 4.
	BMP280Filter4 uint8 = 2
	// BMP280Filter8 selects IIR coefficient 8.
	BMP280Filter8 uint8 = 3
	// BMP280Filter16 selects IIR coefficient 16.
	BMP280Filter16 uint8 = 4
)

// BMP280 normal-mode standby times.
const (
	// BMP280TSB0p5MS is 0.5 ms standby.
	BMP280TSB0p5MS uint8 = 0
	// BMP280TSB62p5MS is 62.5 ms standby.
	BMP280TSB62p5MS uint8 = 1
	// BMP280TSB125MS is 125 ms standby.
	BMP280TSB125MS uint8 = 2
	// BMP280TSB250MS is 250 ms standby.
	BMP280TSB250MS uint8 = 3
	// BMP280TSB500MS is 500 ms standby.
	BMP280TSB500MS uint8 = 4
	// BMP280TSB1000MS is 1000 ms standby.
	BMP280TSB1000MS uint8 = 5
	// BMP280TSB2000MS is 2000 ms standby.
	BMP280TSB2000MS uint8 = 6
	// BMP280TSB4000MS is 4000 ms standby.
	BMP280TSB4000MS uint8 = 7
)

// BMP280 status register flags.
const (
	// BMP280StatusMeasuring is set while a conversion is running.
	BMP280StatusMeasuring uint8 = 0x08
	// BMP280StatusIMUpdate is set while NVM image is being copied to shadow registers.
	BMP280StatusIMUpdate uint8 = 0x01
)

// bmp280Calibration holds the 12 factory trimming coefficients for the BMP280.
type bmp280Calibration struct {
	digT1 uint16
	digT2 int16
	digT3 int16
	digP1 uint16
	digP2 int16
	digP3 int16
	digP4 int16
	digP5 int16
	digP6 int16
	digP7 int16
	digP8 int16
	digP9 int16
}

// bmp280ReadCalibration reads the 24-byte calibration block (0x88..0x9F) and
// unpacks all 12 little-endian trimming coefficients.
func bmp280ReadCalibration(t transport.Transport) (bmp280Calibration, error) {
	var c bmp280Calibration
	buf, err := t.WriteRead([]byte{bmp280RegCalStart}, 24)
	if err != nil {
		return c, err
	}
	c.digT1 = uint16(buf[0]) | uint16(buf[1])<<8
	c.digT2 = int16(uint16(buf[2]) | uint16(buf[3])<<8)
	c.digT3 = int16(uint16(buf[4]) | uint16(buf[5])<<8)
	c.digP1 = uint16(buf[6]) | uint16(buf[7])<<8
	c.digP2 = int16(uint16(buf[8]) | uint16(buf[9])<<8)
	c.digP3 = int16(uint16(buf[10]) | uint16(buf[11])<<8)
	c.digP4 = int16(uint16(buf[12]) | uint16(buf[13])<<8)
	c.digP5 = int16(uint16(buf[14]) | uint16(buf[15])<<8)
	c.digP6 = int16(uint16(buf[16]) | uint16(buf[17])<<8)
	c.digP7 = int16(uint16(buf[18]) | uint16(buf[19])<<8)
	c.digP8 = int16(uint16(buf[20]) | uint16(buf[21])<<8)
	c.digP9 = int16(uint16(buf[22]) | uint16(buf[23])<<8)
	return c, nil
}

// bmp280CompensateTemp returns (t_fine, temperature_C).
func bmp280CompensateTemp(adcT uint32, c bmp280Calibration) (int32, float32) {
	var1 := int64(((int64(adcT) >> 3) - (int64(c.digT1) << 1)) * int64(c.digT2)) >> 11
	var2 := int64(((int64(adcT)>>4)-int64(c.digT1))*((int64(adcT)>>4)-int64(c.digT1))>>12) * int64(c.digT3) >> 14
	tFine := int32(var1 + var2)
	temp := float32((int64(tFine)*5+128)>>8) / 100.0
	return tFine, temp
}

// bmp280CompensatePressure returns pressure in hPa. 64-bit integer math.
func bmp280CompensatePressure(adcP uint32, tFine int32, c bmp280Calibration) float32 {
	t := int64(tFine)
	var1 := t - 128000
	var2 := var1 * var1 * int64(c.digP6)
	var2 = var2 + ((var1 * int64(c.digP5)) << 17)
	var2 = var2 + (int64(c.digP4) << 35)
	var1 = ((var1*var1*int64(c.digP3))>>8) + ((var1 * int64(c.digP2)) << 12)
	var1 = (((int64(1) << 47) + var1) * int64(c.digP1)) >> 33
	if var1 == 0 {
		return 0
	}
	p := int64(1048576) - int64(adcP)
	p = (((p << 31) - var2) * 3125) / var1
	var1 = (int64(c.digP9) * ((p >> 13) * (p >> 13)) >> 13) >> 12
	var2 = (int64(c.digP8) * p) >> 19
	p = ((p + var1 + var2) >> 8) + (int64(c.digP7) << 4)
	return float32(float64(p) / 256.0 / 100.0)
}

// BMP280Minimal is the BMP280 combined pressure + temperature driver —
// minimal interface.
//
// The default configuration baked in is: forced mode, osrs_t=×1, osrs_p=×1
// (ultra-low-power preset, ~6 ms per cycle), IIR filter off. Each call to
// Temperature or Pressure triggers a fresh forced measurement and reads both
// ADCs in one burst.
type BMP280Minimal struct {
	transport transport.Transport

	osrsT  uint8
	osrsP  uint8
	mode   uint8
	filter uint8
	tSB    uint8

	tFine int32
	cal   bmp280Calibration
}

// NewBMP280Minimal creates a BMP280Minimal, reads the 12 trimming
// coefficients, and applies the default ultra-low-power configuration.
//
// transport must be a configured I²C transport bound to the chip's 7-bit
// address (0x76 or 0x77).
func NewBMP280Minimal(t transport.Transport) (*BMP280Minimal, error) {
	cal, err := bmp280ReadCalibration(t)
	if err != nil {
		return nil, err
	}
	d := &BMP280Minimal{
		transport: t,
		osrsT:     BMP280OSRSX1,
		osrsP:     BMP280OSRSX1,
		mode:      BMP280ModeSleep,
		filter:    BMP280FilterOff,
		tSB:       BMP280TSB0p5MS,
		cal:       cal,
	}
	if err := d.writeReg(bmp280RegCtrlMeas, (d.osrsT<<5)|(d.osrsP<<2)|0); err != nil {
		return nil, err
	}
	if err := d.writeReg(bmp280RegConfig, 0); err != nil {
		return nil, err
	}
	return d, nil
}

// writeReg writes a single byte to a register.
func (d *BMP280Minimal) writeReg(reg, val uint8) error {
	return d.transport.Write([]byte{reg, val})
}

// readReg8 reads a single byte from a register.
func (d *BMP280Minimal) readReg8(reg uint8) (uint8, error) {
	b, err := d.transport.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// triggerAndRead triggers a forced measurement (unless in normal mode) and
// returns the raw 20-bit pressure and 20-bit temperature ADC values from
// one burst read of registers 0xF7..0xFC.
func (d *BMP280Minimal) triggerAndRead() (uint32, uint32, error) {
	if d.mode != BMP280ModeNormal {
		ctrl := (d.osrsT << 5) | (d.osrsP << 2) | BMP280ModeForced
		if err := d.writeReg(bmp280RegCtrlMeas, ctrl); err != nil {
			return 0, 0, err
		}
		time.Sleep(bmp280MeasTime)
	}
	raw, err := d.transport.WriteRead([]byte{bmp280RegData}, 6)
	if err != nil {
		return 0, 0, err
	}
	adcP := uint32(raw[0])<<12 | uint32(raw[1])<<4 | uint32(raw[2])>>4
	adcT := uint32(raw[3])<<12 | uint32(raw[4])<<4 | uint32(raw[5])>>4
	return adcP, adcT, nil
}

// Temperature triggers a forced measurement and returns the calibrated
// temperature in degrees Celsius. Caches t_fine for the subsequent pressure
// compensation. Self-contained — may be called without a prior pressure call.
//
// Returns temperature in °C.
func (d *BMP280Minimal) Temperature() (float32, error) {
	_, adcT, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, t := bmp280CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	return t, nil
}

// Pressure triggers a forced measurement and returns the calibrated pressure
// in hPa. Refreshes t_fine from the same temperature ADC sample so the
// pressure compensation is accurate. Self-contained.
//
// Returns pressure in hPa.
func (d *BMP280Minimal) Pressure() (float32, error) {
	adcP, adcT, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, _ := bmp280CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	return bmp280CompensatePressure(adcP, tFine, d.cal), nil
}

// BMP280Full is the BMP280 combined pressure + temperature driver — full
// interface. Extends BMP280Minimal with configuration, status inspection,
// and altitude helpers.
type BMP280Full struct {
	*BMP280Minimal
}

// NewBMP280Full creates a BMP280Full and applies the default configuration.
//
// transport must be a configured I²C transport bound to the chip's 7-bit
// address (0x76 or 0x77).
func NewBMP280Full(t transport.Transport) (*BMP280Full, error) {
	m, err := NewBMP280Minimal(t)
	if err != nil {
		return nil, err
	}
	return &BMP280Full{BMP280Minimal: m}, nil
}

// Configure writes both the config and ctrl_meas registers.
//
// Parameters:
//   - osrsT  — temperature oversampling (0=skip, 1..5 = ×1..×16)
//   - osrsP  — pressure oversampling (same encoding)
//   - mode   — power mode (BMP280ModeSleep, BMP280ModeForced, BMP280ModeNormal)
//   - filter — IIR filter coefficient (0..4)
//   - tSB    — normal-mode standby (0..7)
func (d *BMP280Full) Configure(osrsT, osrsP, mode, filter, tSB uint8) error {
	d.osrsT = osrsT
	d.osrsP = osrsP
	d.mode = mode
	d.filter = filter
	d.tSB = tSB
	if err := d.writeReg(bmp280RegConfig, (tSB<<5)|(filter<<2)); err != nil {
		return err
	}
	return d.writeReg(bmp280RegCtrlMeas, (osrsT<<5)|(osrsP<<2)|mode)
}

// SetOversampling updates the two oversampling settings.
func (d *BMP280Full) SetOversampling(osrsT, osrsP uint8) error {
	d.osrsT = osrsT
	d.osrsP = osrsP
	return d.writeReg(bmp280RegCtrlMeas, (osrsT<<5)|(osrsP<<2)|d.mode)
}

// SetMode updates the power-mode bits of ctrl_meas.
func (d *BMP280Full) SetMode(mode uint8) error {
	d.mode = mode
	return d.writeReg(bmp280RegCtrlMeas, (d.osrsT<<5)|(d.osrsP<<2)|mode)
}

// SetFilter updates the IIR filter coefficient in the config register.
func (d *BMP280Full) SetFilter(coeff uint8) error {
	d.filter = coeff
	return d.writeReg(bmp280RegConfig, (d.tSB<<5)|(coeff<<2))
}

// SetStandby updates the normal-mode standby time.
func (d *BMP280Full) SetStandby(tSB uint8) error {
	d.tSB = tSB
	return d.writeReg(bmp280RegConfig, (tSB<<5)|(d.filter<<2))
}

// Status reads the status register at 0xF3. Bit 3 = measuring, bit 0 = im_update.
func (d *BMP280Full) Status() (uint8, error) {
	return d.readReg8(bmp280RegStatus)
}

// Altitude computes altitude above sea level from the current pressure
// using the international barometric formula.
//
// seaLevelHPa is the reference pressure (default 1013.25).
//
// Returns altitude in metres.
func (d *BMP280Full) Altitude(seaLevelHPa float32) (float32, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	ratio := float64(p / seaLevelHPa)
	return float32(44330.0 * (1.0 - math.Pow(ratio, 1.0/5.255))), nil
}

// SeaLevelPressure computes the sea-level pressure implied by the current
// pressure reading and a known altitude.
//
// altitudeM is the altitude in metres above sea level.
//
// Returns sea-level pressure in hPa.
func (d *BMP280Full) SeaLevelPressure(altitudeM float32) (float32, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	return p / float32(math.Pow(1.0-float64(altitudeM)/44330.0, 5.255)), nil
}

// ChipID reads the chip ID register at 0xD0. Expect 0x58 for a BMP280.
func (d *BMP280Full) ChipID() (uint8, error) {
	return d.readReg8(bmp280RegID)
}

// Reset performs a soft reset (write 0xB6 to 0xE0), re-reads the
// calibration coefficients, and re-applies the current configuration.
func (d *BMP280Full) Reset() error {
	if err := d.writeReg(bmp280RegReset, bmp280ResetCmd); err != nil {
		return err
	}
	time.Sleep(2 * time.Millisecond)
	cal, err := bmp280ReadCalibration(d.transport)
	if err != nil {
		return err
	}
	d.cal = cal
	if err := d.writeReg(bmp280RegConfig, (d.tSB<<5)|(d.filter<<2)); err != nil {
		return err
	}
	return d.writeReg(bmp280RegCtrlMeas, (d.osrsT<<5)|(d.osrsP<<2)|d.mode)
}

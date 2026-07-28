// Package environmental contains drivers for combined environmental
// sensors (temperature, humidity, pressure).
package environmental

import (
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BME280 register addresses.
const (
	bme280RegCalStart uint8 = 0x88
	bme280RegH1       uint8 = 0xA1
	bme280RegID       uint8 = 0xD0
	bme280RegReset    uint8 = 0xE0
	bme280RegCalH2    uint8 = 0xE1
	bme280RegCtrlHum  uint8 = 0xF2
	bme280RegStatus   uint8 = 0xF3
	bme280RegCtrlMeas uint8 = 0xF4
	bme280RegConfig   uint8 = 0xF5
	bme280RegData     uint8 = 0xF7
)

// BME280 expected chip ID.
const bme280ChipID uint8 = 0x60

// BME280 soft-reset command byte.
const bme280ResetCmd uint8 = 0xB6

// BME280 forced-mode measurement time (weather-monitoring preset: 9 ms max).
const bme280MeasTime = 10 * time.Millisecond

// BME280 oversampling modes.
const (
	// BME280OSRSSkip disables the corresponding channel.
	BME280OSRSSkip uint8 = 0
	// BME280OSRSX1 is ×1 oversampling.
	BME280OSRSX1 uint8 = 1
	// BME280OSRSX2 is ×2 oversampling.
	BME280OSRSX2 uint8 = 2
	// BME280OSRSX4 is ×4 oversampling.
	BME280OSRSX4 uint8 = 3
	// BME280OSRSX8 is ×8 oversampling.
	BME280OSRSX8 uint8 = 4
	// BME280OSRSX16 is ×16 oversampling.
	BME280OSRSX16 uint8 = 5
)

// BME280 power modes.
const (
	// BME280ModeSleep is the lowest-power mode; the chip idles.
	BME280ModeSleep uint8 = 0
	// BME280ModeForced triggers a single-shot measurement and returns to sleep.
	BME280ModeForced uint8 = 1
	// BME280ModeNormal makes the chip cycle continuously between standby and measurement.
	BME280ModeNormal uint8 = 3
)

// BME280 IIR filter coefficients.
const (
	// BME280FilterOff disables the IIR filter.
	BME280FilterOff uint8 = 0
	// BME280Filter2 selects IIR coefficient 2.
	BME280Filter2 uint8 = 1
	// BME280Filter4 selects IIR coefficient 4.
	BME280Filter4 uint8 = 2
	// BME280Filter8 selects IIR coefficient 8.
	BME280Filter8 uint8 = 3
	// BME280Filter16 selects IIR coefficient 16.
	BME280Filter16 uint8 = 4
)

// BME280 normal-mode standby times (t_sb codes; codes 6/7 are 10/20 ms — BME280-specific).
const (
	// BME280TSB0p5MS is 0.5 ms standby.
	BME280TSB0p5MS uint8 = 0
	// BME280TSB62p5MS is 62.5 ms standby.
	BME280TSB62p5MS uint8 = 1
	// BME280TSB125MS is 125 ms standby.
	BME280TSB125MS uint8 = 2
	// BME280TSB250MS is 250 ms standby.
	BME280TSB250MS uint8 = 3
	// BME280TSB500MS is 500 ms standby.
	BME280TSB500MS uint8 = 4
	// BME280TSB1000MS is 1000 ms standby.
	BME280TSB1000MS uint8 = 5
	// BME280TSB10MS is 10 ms standby (BME280-specific; BMP280 code 6 = 2000 ms).
	BME280TSB10MS uint8 = 6
	// BME280TSB20MS is 20 ms standby (BME280-specific; BMP280 code 7 = 4000 ms).
	BME280TSB20MS uint8 = 7
)

// BME280 status register flags.
const (
	// BME280StatusMeasuring is set while a conversion is running.
	BME280StatusMeasuring uint8 = 0x08
	// BME280StatusIMUpdate is set while NVM image is being copied to shadow registers.
	BME280StatusIMUpdate uint8 = 0x01
)

// bme280Calibration holds the 18 factory trimming coefficients for the BME280.
type bme280Calibration struct {
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
	digH1 uint8
	digH2 int16
	digH3 uint8
	digH4 int16
	digH5 int16
	digH6 int8
}

// signExtend12 zero- or sign-extends a 12-bit value to int16.
func signExtend12(raw uint16) int16 {
	if raw&0x800 != 0 {
		return int16(raw | 0xF000)
	}
	return int16(raw)
}

// bme280ReadCalibration reads both calibration blocks (26 + 7 bytes) and unpacks
// all 18 trimming coefficients. Block 1 (0x88..0xA1) holds the 16-bit temperature
// and pressure coefficients (little-endian) plus dig_H1 at 0xA1. Block 2
// (0xE1..0xE7) holds dig_H2..dig_H6 with dig_H4 and dig_H5 sharing register
// 0xE5 — both are 12-bit two's-complement values.
func bme280ReadCalibration(t connection.Connection) (bme280Calibration, error) {
	var c bme280Calibration
	// Block 1: 0x88..0xA0 = 25 bytes, skip reserved 0xA0, plus 0xA1 (dig_H1).
	// Burst-read 26 bytes starting at 0x88 (this crosses the reserved 0xA0).
	b1, err := t.WriteRead([]byte{bme280RegCalStart}, 26)
	if err != nil {
		return c, err
	}
	c.digT1 = uint16(b1[0]) | uint16(b1[1])<<8
	c.digT2 = int16(uint16(b1[2]) | uint16(b1[3])<<8)
	c.digT3 = int16(uint16(b1[4]) | uint16(b1[5])<<8)
	c.digP1 = uint16(b1[6]) | uint16(b1[7])<<8
	c.digP2 = int16(uint16(b1[8]) | uint16(b1[9])<<8)
	c.digP3 = int16(uint16(b1[10]) | uint16(b1[11])<<8)
	c.digP4 = int16(uint16(b1[12]) | uint16(b1[13])<<8)
	c.digP5 = int16(uint16(b1[14]) | uint16(b1[15])<<8)
	c.digP6 = int16(uint16(b1[16]) | uint16(b1[17])<<8)
	c.digP7 = int16(uint16(b1[18]) | uint16(b1[19])<<8)
	c.digP8 = int16(uint16(b1[20]) | uint16(b1[21])<<8)
	c.digP9 = int16(uint16(b1[22]) | uint16(b1[23])<<8)
	c.digH1 = b1[25] // skip reserved 0xA0 at b1[24]
	// Block 2: 0xE1..0xE7 = 7 bytes.
	b2, err := t.WriteRead([]byte{bme280RegCalH2}, 7)
	if err != nil {
		return c, err
	}
	c.digH2 = int16(uint16(b2[0]) | uint16(b2[1])<<8)
	c.digH3 = b2[2]
	c.digH4 = signExtend12((uint16(b2[3]) << 4) | (uint16(b2[4]) & 0x0F))
	c.digH5 = signExtend12((uint16(b2[5]) << 4) | (uint16(b2[4]) >> 4))
	c.digH6 = int8(b2[6])
	return c, nil
}

// bme280CompensateTemp returns (t_fine, temperature_C).
func bme280CompensateTemp(adcT uint32, c bme280Calibration) (int32, float32) {
	// Use 64-bit intermediates per datasheet section 4.2.3.
	var1 := int64(((int64(adcT) >> 3) - (int64(c.digT1) << 1)) * int64(c.digT2)) >> 11
	var2 := int64(((int64(adcT)>>4)-int64(c.digT1))*((int64(adcT)>>4)-int64(c.digT1))>>12) * int64(c.digT3) >> 14
	tFine := int32(var1 + var2)
	temp := float32((int64(tFine)*5+128)>>8) / 100.0
	return tFine, temp
}

// bme280CompensatePressure returns pressure in hPa. 64-bit integer math.
func bme280CompensatePressure(adcP uint32, tFine int32, c bme280Calibration) float32 {
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

// bme280CompensateHumidity returns humidity in %RH. 32-bit integer math per
// datasheet section 4.2.3.
func bme280CompensateHumidity(adcH uint16, tFine int32, c bme280Calibration) float32 {
	v := tFine - 76800
	vX1 := int32(((int32(adcH) << 14) - (int32(c.digH4) << 20) - (int32(c.digH5) * v) + 16384) >> 15)
	v = ((v * int32(c.digH6)) >> 10) * (((v * int32(c.digH3)) >> 11) + 32768)
	v = v >> 10
	v = v + 2097152
	v = ((v * int32(c.digH2)) + 8192) >> 14
	v = vX1 * v
	vX2 := (v >> 15) * (v >> 15)
	v = v - (((vX2 >> 7) * int32(c.digH1)) >> 4)
	if v < 0 {
		v = 0
	}
	if v > 419430400 {
		v = 419430400
	}
	return float32(v>>12) / 1024.0
}

// BME280Minimal is the BME280 combined humidity + pressure + temperature
// driver — minimal interface.
//
// The default configuration baked in is: forced mode, osrs_t=×1, osrs_p=×1,
// osrs_h=×1 (weather-monitoring preset, ~9 ms per cycle), IIR filter off.
// Each call to Temperature, Pressure, or Humidity triggers a fresh forced
// measurement and reads all three ADCs in one burst.
type BME280Minimal struct {
	connection connection.Connection

	osrsT  uint8
	osrsP  uint8
	osrsH  uint8
	mode   uint8
	filter uint8
	tSB    uint8

	tFine int32
	cal   bme280Calibration
}

// NewBME280Minimal creates a BME280Minimal, reads the 18 trimming
// coefficients, and applies the default weather-monitoring configuration.
//
// connection must be a configured I²C connection bound to the chip's 7-bit
// address (0x76 or 0x77).
func NewBME280Minimal(t connection.Connection) (*BME280Minimal, error) {
	cal, err := bme280ReadCalibration(t)
	if err != nil {
		return nil, err
	}
	d := &BME280Minimal{
		connection: t,
		osrsT:     BME280OSRSX1,
		osrsP:     BME280OSRSX1,
		osrsH:     BME280OSRSX1,
		mode:      BME280ModeSleep,
		filter:    BME280FilterOff,
		tSB:       BME280TSB0p5MS,
		cal:       cal,
	}
	// ctrl_hum must be written before ctrl_meas for the value to latch.
	if err := d.writeReg(bme280RegCtrlHum, d.osrsH); err != nil {
		return nil, err
	}
	if err := d.writeReg(bme280RegCtrlMeas, (d.osrsT<<5)|(d.osrsP<<2)|0); err != nil {
		return nil, err
	}
	if err := d.writeReg(bme280RegConfig, 0); err != nil {
		return nil, err
	}
	return d, nil
}

// writeReg writes a single byte to a register.
func (d *BME280Minimal) writeReg(reg, val uint8) error {
	return d.connection.Write([]byte{reg, val})
}

// readReg8 reads a single byte from a register.
func (d *BME280Minimal) readReg8(reg uint8) (uint8, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// triggerAndRead triggers a forced measurement (unless in normal mode) and
// returns the raw 20-bit pressure, 20-bit temperature, and 16-bit humidity
// ADC values from one burst read of registers 0xF7..0xFE.
func (d *BME280Minimal) triggerAndRead() (uint32, uint32, uint16, error) {
	if d.mode != BME280ModeNormal {
		if err := d.writeReg(bme280RegCtrlHum, d.osrsH); err != nil {
			return 0, 0, 0, err
		}
		ctrl := (d.osrsT << 5) | (d.osrsP << 2) | BME280ModeForced
		if err := d.writeReg(bme280RegCtrlMeas, ctrl); err != nil {
			return 0, 0, 0, err
		}
		time.Sleep(bme280MeasTime)
	}
	raw, err := d.connection.WriteRead([]byte{bme280RegData}, 8)
	if err != nil {
		return 0, 0, 0, err
	}
	adcP := uint32(raw[0])<<12 | uint32(raw[1])<<4 | uint32(raw[2])>>4
	adcT := uint32(raw[3])<<12 | uint32(raw[4])<<4 | uint32(raw[5])>>4
	adcH := uint16(raw[6])<<8 | uint16(raw[7])
	return adcP, adcT, adcH, nil
}

// Temperature triggers a forced measurement and returns the calibrated
// temperature in degrees Celsius. Caches t_fine for the subsequent pressure
// and humidity compensation. Self-contained — may be called without a prior
// pressure or humidity call.
//
// Returns temperature in °C.
func (d *BME280Minimal) Temperature() (float32, error) {
	_, adcT, _, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, t := bme280CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	return t, nil
}

// Pressure triggers a forced measurement and returns the calibrated
// pressure in hPa. Refreshes t_fine from the same temperature ADC sample
// so the pressure compensation is accurate. Self-contained.
//
// Returns pressure in hPa.
func (d *BME280Minimal) Pressure() (float32, error) {
	adcP, adcT, _, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, _ := bme280CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	return bme280CompensatePressure(adcP, tFine, d.cal), nil
}

// Humidity triggers a forced measurement and returns the calibrated
// relative humidity in %RH. Refreshes t_fine. Self-contained.
//
// Returns humidity in %RH.
func (d *BME280Minimal) Humidity() (float32, error) {
	_, adcT, adcH, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, _ := bme280CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	return bme280CompensateHumidity(adcH, tFine, d.cal), nil
}

// BME280Full is the BME280 combined humidity + pressure + temperature
// driver — full interface. Extends BME280Minimal with configuration,
// status inspection, and altitude/dew-point helpers.
type BME280Full struct {
	*BME280Minimal
}

// NewBME280Full creates a BME280Full and applies the default configuration.
//
// connection must be a configured I²C connection bound to the chip's 7-bit
// address (0x76 or 0x77).
func NewBME280Full(t connection.Connection) (*BME280Full, error) {
	m, err := NewBME280Minimal(t)
	if err != nil {
		return nil, err
	}
	return &BME280Full{BME280Minimal: m}, nil
}

// Configure writes ctrl_hum, config, and ctrl_meas in the correct order.
//
// Parameters:
//   - osrsT  — temperature oversampling (0=skip, 1..5 = ×1..×16)
//   - osrsP  — pressure oversampling (same encoding)
//   - osrsH  — humidity oversampling (same encoding)
//   - mode   — power mode (BME280ModeSleep, BME280ModeForced, BME280ModeNormal)
//   - filter — IIR filter coefficient (0..4)
//   - tSB    — normal-mode standby (0..7; codes 6/7 are 10/20 ms on the BME280)
func (d *BME280Full) Configure(osrsT, osrsP, osrsH, mode, filter, tSB uint8) error {
	d.osrsT = osrsT
	d.osrsP = osrsP
	d.osrsH = osrsH
	d.mode = mode
	d.filter = filter
	d.tSB = tSB
	if err := d.writeReg(bme280RegCtrlHum, osrsH); err != nil {
		return err
	}
	if err := d.writeReg(bme280RegConfig, (tSB<<5)|(filter<<2)); err != nil {
		return err
	}
	return d.writeReg(bme280RegCtrlMeas, (osrsT<<5)|(osrsP<<2)|mode)
}

// SetOversampling updates the three oversampling settings. The chip requires
// a write to ctrl_meas after every ctrl_hum change for it to latch, so
// ctrl_hum is always written first.
func (d *BME280Full) SetOversampling(osrsT, osrsP, osrsH uint8) error {
	d.osrsT = osrsT
	d.osrsP = osrsP
	d.osrsH = osrsH
	if err := d.writeReg(bme280RegCtrlHum, osrsH); err != nil {
		return err
	}
	return d.writeReg(bme280RegCtrlMeas, (osrsT<<5)|(osrsP<<2)|d.mode)
}

// SetMode updates the power-mode bits of ctrl_meas.
func (d *BME280Full) SetMode(mode uint8) error {
	d.mode = mode
	return d.writeReg(bme280RegCtrlMeas, (d.osrsT<<5)|(d.osrsP<<2)|mode)
}

// SetFilter updates the IIR filter coefficient in the config register.
func (d *BME280Full) SetFilter(coeff uint8) error {
	d.filter = coeff
	return d.writeReg(bme280RegConfig, (d.tSB<<5)|(coeff<<2))
}

// SetStandby updates the normal-mode standby time. Codes 6/7 are 10 ms /
// 20 ms on the BME280 (BMP280 uses 2000/4000 ms for the same codes).
func (d *BME280Full) SetStandby(tSB uint8) error {
	d.tSB = tSB
	return d.writeReg(bme280RegConfig, (tSB<<5)|(d.filter<<2))
}

// Status reads the status register at 0xF3. Bit 3 = measuring, bit 0 = im_update.
func (d *BME280Full) Status() (uint8, error) {
	return d.readReg8(bme280RegStatus)
}

// Altitude computes altitude above sea level from the current pressure
// using the international barometric formula.
//
// seaLevelHPa is the reference pressure (default 1013.25).
//
// Returns altitude in metres.
func (d *BME280Full) Altitude(seaLevelHPa float32) (float32, error) {
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
func (d *BME280Full) SeaLevelPressure(altitudeM float32) (float32, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	return p / float32(math.Pow(1.0-float64(altitudeM)/44330.0, 5.255)), nil
}

// DewPoint computes the dew-point temperature from the current
// temperature and humidity using the Magnus-Tetens approximation
// (accurate to ±0.4 °C in the 0–60 °C / 1–100 %RH range).
//
// Returns dew point in °C.
func (d *BME280Full) DewPoint() (float32, error) {
	t, err := d.Temperature()
	if err != nil {
		return 0, err
	}
	h, err := d.Humidity()
	if err != nil {
		return 0, err
	}
	if h <= 0 {
		return float32(math.Inf(-1)), nil
	}
	a := 17.27
	b := 237.7
	alpha := (a*float64(t))/(b+float64(t)) + math.Log(float64(h)/100.0)
	return float32((b * alpha) / (a - alpha)), nil
}

// ChipID reads the chip ID register at 0xD0. Expect 0x60 for a BME280.
func (d *BME280Full) ChipID() (uint8, error) {
	return d.readReg8(bme280RegID)
}

// Reset performs a soft reset (write 0xB6 to 0xE0), re-reads the
// calibration coefficients, and re-applies the current configuration.
func (d *BME280Full) Reset() error {
	if err := d.writeReg(bme280RegReset, bme280ResetCmd); err != nil {
		return err
	}
	time.Sleep(2 * time.Millisecond)
	cal, err := bme280ReadCalibration(d.connection)
	if err != nil {
		return err
	}
	d.cal = cal
	if err := d.writeReg(bme280RegCtrlHum, d.osrsH); err != nil {
		return err
	}
	if err := d.writeReg(bme280RegConfig, (d.tSB<<5)|(d.filter<<2)); err != nil {
		return err
	}
	return d.writeReg(bme280RegCtrlMeas, (d.osrsT<<5)|(d.osrsP<<2)|d.mode)
}

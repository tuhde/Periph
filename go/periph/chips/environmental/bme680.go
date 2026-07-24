// Package environmental contains drivers for combined environmental
// sensors (temperature, humidity, pressure).
package environmental

import (
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/transport"
)

// BME680 register addresses (I²C; the chip has a flat address space on I²C).
const (
	bme680RegResHeatVal   uint8 = 0x00
	bme680RegResHeatRange uint8 = 0x02
	bme680RegRangeSwErr   uint8 = 0x04
	bme680RegMeasStatus   uint8 = 0x1D
	bme680RegPressMsb     uint8 = 0x1F
	bme680RegResHeat0     uint8 = 0x5A
	bme680RegGasWait0     uint8 = 0x64
	bme680RegCtrlGas0     uint8 = 0x70
	bme680RegCtrlGas1     uint8 = 0x71
	bme680RegCtrlHum      uint8 = 0x72
	bme680RegCtrlMeas     uint8 = 0x74
	bme680RegConfig       uint8 = 0x75
	bme680RegCalBlock1    uint8 = 0x8A
	bme680RegID           uint8 = 0xD0
	bme680RegReset        uint8 = 0xE0
	bme680RegCalBlock2    uint8 = 0xE1
)

// BME680 expected chip ID.
const bme680ChipID uint8 = 0x61

// BME680 soft-reset command byte.
const bme680ResetCmd uint8 = 0xB6

// BME680 total forced-cycle wall clock at default settings: TPH + gas wait + ~15 ms.
const bme680MeasTime = 200 * time.Millisecond

// BME680 default heater target (datasheet recommendation) and duration.
const (
	bme680DefaultHeaterTempC = 320
	bme680DefaultHeaterDurMs = 150
	bme680DefaultAmbientC    = 25
)

// BME680 oversampling modes.
const (
	// BME680OSRSSkip disables the corresponding channel.
	BME680OSRSSkip uint8 = 0
	// BME680OSRSX1 is ×1 oversampling.
	BME680OSRSX1 uint8 = 1
	// BME680OSRSX2 is ×2 oversampling.
	BME680OSRSX2 uint8 = 2
	// BME680OSRSX4 is ×4 oversampling.
	BME680OSRSX4 uint8 = 3
	// BME680OSRSX8 is ×8 oversampling.
	BME680OSRSX8 uint8 = 4
	// BME680OSRSX16 is ×16 oversampling.
	BME680OSRSX16 uint8 = 5
)

// BME680 power modes (only sleep and forced — no normal mode).
const (
	// BME680ModeSleep is the lowest-power mode; the chip idles.
	BME680ModeSleep uint8 = 0
	// BME680ModeForced triggers a single-shot measurement and returns to sleep.
	BME680ModeForced uint8 = 1
)

// BME680 IIR filter coefficients (8 settings, applied to T and P only).
const (
	// BME680Filter0 disables the IIR filter.
	BME680Filter0 uint8 = 0
	// BME680Filter1 selects IIR coefficient 1.
	BME680Filter1 uint8 = 1
	// BME680Filter3 selects IIR coefficient 3.
	BME680Filter3 uint8 = 2
	// BME680Filter7 selects IIR coefficient 7.
	BME680Filter7 uint8 = 3
	// BME680Filter15 selects IIR coefficient 15.
	BME680Filter15 uint8 = 4
	// BME680Filter31 selects IIR coefficient 31.
	BME680Filter31 uint8 = 5
	// BME680Filter63 selects IIR coefficient 63.
	BME680Filter63 uint8 = 6
	// BME680Filter127 selects IIR coefficient 127.
	BME680Filter127 uint8 = 7
)

// BME680 status-register flags.
const (
	// BME680StatusNewData indicates new TPH data is available.
	BME680StatusNewData uint8 = 0x80
	// BME680StatusGasMeasuring indicates a gas conversion is in progress.
	BME680StatusGasMeasuring uint8 = 0x40
	// BME680StatusMeasuring indicates any conversion is in progress.
	BME680StatusMeasuring uint8 = 0x20
	// BME680StatusGasValid is set in gas_r_lsb when the gas reading is real.
	BME680StatusGasValid uint8 = 0x20
	// BME680StatusHeaterStable is set in gas_r_lsb when the heater reached target.
	BME680StatusHeaterStable uint8 = 0x10
)

// bme680ConstArray1 and bme680ConstArray2 are the 16-entry lookup tables from
// datasheet Table 16, used by the gas-resistance compensation.
var (
	bme680ConstArray1 = [16]int64{
		2147483647, 2147483647, 2147483647, 2147483647, 2147483647,
		2126008810, 2147483647, 2130303777, 2147483647, 2147483647,
		2143188679, 2136746228, 2147483647, 2126008810, 2147483647,
		2147483647,
	}
	bme680ConstArray2 = [16]int64{
		4096000000, 2048000000, 1024000000, 512000000, 255744255,
		127110228, 64000000, 32258064, 16016016, 8000000,
		4000000, 2000000, 1000000, 500000, 250000,
		125000,
	}
)

// bme680Calibration holds all 28 factory trimming coefficients and the
// three single-byte calibration values for the BME680.
type bme680Calibration struct {
	parT1 uint16
	parT2 int16
	parT3 int8
	parP1 uint16
	parP2 int16
	parP3 int8
	parP4 int16
	parP5 int16
	parP6 int8
	parP7 int8
	parP8 int16
	parP9 int16
	parP10 uint8
	parH1 uint16
	parH2 uint16
	parH3 int8
	parH4 int8
	parH5 int8
	parH6 uint8
	parH7 int8
	parG1 int8
	parG2 int16
	parG3 int8

	resHeatVal          int8
	resHeatRange        uint8
	rangeSwitchingError int8
}

// bme680ReadCalibration reads the two non-contiguous calibration blocks plus
// the three single-byte calibration registers. Block 1 (0x8A..0xA0) holds the
// 23 bytes for the T and P coefficients (with the unusual P7-below-P6
// ordering). Block 2 (0xE1..0xEE) holds 14 bytes for the H, T1, and G
// coefficients — note that par_H1 and par_H2 share register 0xE2 (par_H2 in
// the high nibble, par_H1 in the low nibble).
func bme680ReadCalibration(t transport.Transport) (bme680Calibration, error) {
	var c bme680Calibration
	b1, err := t.WriteRead([]byte{bme680RegCalBlock1}, 23)
	if err != nil {
		return c, err
	}
	b2, err := t.WriteRead([]byte{bme680RegCalBlock2}, 14)
	if err != nil {
		return c, err
	}
	s1, err := t.WriteRead([]byte{bme680RegResHeatVal}, 1)
	if err != nil {
		return c, err
	}
	s2, err := t.WriteRead([]byte{bme680RegResHeatRange}, 1)
	if err != nil {
		return c, err
	}
	s3, err := t.WriteRead([]byte{bme680RegRangeSwErr}, 1)
	if err != nil {
		return c, err
	}

	c.parT2 = int16(uint16(b1[0]) | uint16(b1[1])<<8)
	c.parT3 = int8(b1[2])
	// b1[3] is reserved.
	c.parP1 = uint16(b1[4]) | uint16(b1[5])<<8
	c.parP2 = int16(uint16(b1[6]) | uint16(b1[7])<<8)
	c.parP3 = int8(b1[8])
	// b1[9] is reserved.
	c.parP4 = int16(uint16(b1[10]) | uint16(b1[11])<<8)
	c.parP5 = int16(uint16(b1[12]) | uint16(b1[13])<<8)
	c.parP7 = int8(b1[14])
	c.parP6 = int8(b1[15])
	// b1[16], b1[17] are reserved.
	c.parP8 = int16(uint16(b1[18]) | uint16(b1[19])<<8)
	c.parP9 = int16(uint16(b1[20]) | uint16(b1[21])<<8)
	c.parP10 = b1[22]

	// par_H2 uses the high nibble of 0xE2 plus 0xE1; par_H1 uses the low nibble
	// of 0xE2 plus 0xE3. Both 12-bit, unsigned.
	c.parH2 = (uint16(b2[0]) << 4) | (uint16(b2[1]) >> 4)
	c.parH1 = (uint16(b2[2]) << 4) | (uint16(b2[1]) & 0x0F)
	c.parH3 = int8(b2[3])
	c.parH4 = int8(b2[4])
	c.parH5 = int8(b2[5])
	c.parH6 = b2[6]
	c.parH7 = int8(b2[7])
	// par_T1 lives at 0xE9..0xEA — *not* at 0x88 like the BME280.
	c.parT1 = uint16(b2[8]) | uint16(b2[9])<<8
	c.parG2 = int16(uint16(b2[10]) | uint16(b2[11])<<8)
	c.parG1 = int8(b2[12])
	c.parG3 = int8(b2[13])

	c.resHeatVal = int8(s1[0])
	c.resHeatRange = (s2[0] >> 4) & 0x03
	rse := (s3[0] >> 4) & 0x0F
	// 4-bit signed: if the high bit is set, sign-extend from bit 3.
	if rse >= 8 {
		c.rangeSwitchingError = int8(rse) - 16
	} else {
		c.rangeSwitchingError = int8(rse)
	}
	return c, nil
}

// bme680CompensateTemp returns (t_fine, temperature_C).
func bme680CompensateTemp(adcT uint32, c bme680Calibration) (int32, float32) {
	parT1 := int32(c.parT1)
	parT2 := int32(c.parT2)
	parT3 := int32(c.parT3)
	adc := int32(adcT)
	var1 := (adc >> 3) - (parT1 << 1)
	var2 := (var1 * parT2) >> 11
	var3 := (((var1 >> 1) * (var1 >> 1)) >> 12) * (parT3 << 4) >> 14
	tFine := var2 + var3
	temp := float32((tFine*5+128)>>8) / 100.0
	return tFine, temp
}

// bme680CompensatePressure returns pressure in hPa. 32-bit integer math.
func bme680CompensatePressure(adcP uint32, tFine int32, c bme680Calibration) float32 {
	parP1 := int32(c.parP1)
	parP2 := int32(c.parP2)
	parP3 := int32(c.parP3)
	parP4 := int32(c.parP4)
	parP5 := int32(c.parP5)
	parP6 := int32(c.parP6)
	parP7 := int32(c.parP7)
	parP8 := int32(c.parP8)
	parP9 := int32(c.parP9)
	parP10 := int32(c.parP10)

	var1 := (tFine >> 1) - 64000
	var2 := ((((var1 >> 2) * (var1 >> 2)) >> 11) * parP6) >> 2
	var2 = var2 + ((var1 * parP5) << 1)
	var2 = (var2 >> 2) + (parP4 << 16)
	var1 = (((((var1 >> 2) * (var1 >> 2)) >> 13) * (parP3 << 5)) >> 3) + ((parP2 * var1) >> 1)
	var1 = var1 >> 18
	var1 = ((32768 + var1) * parP1) >> 15
	pressComp := int32(1048576) - int32(adcP)
	pressComp = int32((int64(pressComp-(var2>>12)) * 3125))
	if pressComp >= (1 << 30) {
		pressComp = (pressComp / var1) << 1
	} else {
		pressComp = (pressComp << 1) / var1
	}
	var1 = (parP9 * (((pressComp >> 3) * (pressComp >> 3)) >> 13)) >> 12
	var2 = ((pressComp >> 2) * parP8) >> 13
	var3 := ((pressComp >> 8) * (pressComp >> 8) * (pressComp >> 8) * parP10) >> 17
	pressComp = pressComp + ((var1 + var2 + var3 + (parP7 << 7)) >> 4)
	return float32(pressComp) / 100.0
}

// bme680CompensateHumidity returns humidity in %RH. 32-bit integer math
// using 64-bit intermediates to avoid overflow on the temp-scaled
// multiplication.
func bme680CompensateHumidity(adcH uint16, tFine int32, c bme680Calibration) float32 {
	tempScaled := int64(tFine)
	parH1 := int64(c.parH1)
	parH2 := int64(c.parH2)
	parH3 := int64(c.parH3)
	parH4 := int64(c.parH4)
	parH5 := int64(c.parH5)
	parH6 := int64(c.parH6)
	parH7 := int64(c.parH7)

	var1 := int64(adcH) - ((parH1 << 4) + (((tempScaled * parH3) / 100) >> 1))
	var2 := (parH2 * (((tempScaled * parH4) / 100) +
		(((tempScaled*((tempScaled*parH5)/100))>>6)/100) +
		(1 << 14))) >> 10
	var3 := var1 * var2
	var4 := ((parH6 << 7) + ((tempScaled * parH7) / 100)) >> 4
	var5 := ((var3 >> 14) * (var3 >> 14)) >> 10
	var6 := (var4 * var5) >> 1
	humComp := (((var3 + var6) >> 10) * 1000) >> 12
	if humComp < 0 {
		humComp = 0
	}
	if humComp > 100000 {
		humComp = 100000
	}
	return float32(humComp) / 1000.0
}

// bme680CompensateGas returns gas resistance in Ohms, or NaN on invalid
// inputs. Uses 64-bit math because the intermediate can grow large.
func bme680CompensateGas(adcG uint16, gasRange uint8, rse int8) float32 {
	rse64 := int64(rse)
	var1 := ((1340 + 5*rse64) * bme680ConstArray1[gasRange]) >> 16
	var2 := ((int64(adcG) << 15) - (int64(1) << 24)) + var1
	if var2 == 0 {
		return float32(math.NaN())
	}
	gasRes := ((bme680ConstArray2[gasRange] * var1) >> 9) + (var2 >> 1)
	return float32(gasRes / var2)
}

// bme680CalcHeaterResistance computes the res_heat_x register value for a
// given target heater temperature and ambient temperature.
func bme680CalcHeaterResistance(targetTemp int16, ambientC float32, c bme680Calibration) uint8 {
	parG1 := int32(c.parG1)
	parG2 := int32(c.parG2)
	parG3 := int32(c.parG3)
	rhr := int32(c.resHeatRange)
	rhv := int32(c.resHeatVal)

	var1 := ((int32(ambientC) * parG3) / 10) << 8
	var2 := (parG1 + 784) * ((((parG2+154009)*int32(targetTemp)*5)/100 + 3276800) / 10)
	var3 := var1 + (var2 >> 1)
	var4 := var3 / (rhr + 4)
	var5 := (131 * rhv) + 65536
	resHeatX100 := ((var4 / var5) - 250) * 34
	resHeatX := (resHeatX100 + 50) / 100
	return uint8(int32(0xFF) & resHeatX)
}

// bme680CalcGasWait encodes a target on-time in ms into the gas_wait_x
// format: 6-bit timer × 2-bit multiplier.
func bme680CalcGasWait(targetMs uint16) uint8 {
	if targetMs <= 0x3F {
		return uint8(targetMs)
	} else if targetMs <= 0x3F*4 {
		return (1 << 6) | uint8(targetMs/4)
	} else if targetMs <= 0x3F*16 {
		return (2 << 6) | uint8(targetMs/16)
	} else {
		v := targetMs / 64
		if v > 0x3F {
			v = 0x3F
		}
		return (3 << 6) | uint8(v)
	}
}

// BME680Minimal is the BME680 4-in-1 environmental driver — minimal interface.
//
// Default: forced mode, osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
// heater profile 0 at 320 °C / 150 ms with gas conversion enabled. Each
// call to Temperature, Pressure, Humidity, or GasResistance triggers a fresh
// forced-mode TPHG cycle and reads all 13 output bytes (0x1F..0x2B) in one
// burst.
type BME680Minimal struct {
	transport transport.Transport

	osrsT  uint8
	osrsP  uint8
	osrsH  uint8
	filter uint8
	mode   uint8

	ambientC  float32
	heatTemp  int16
	heatDur   uint16
	gasOn     bool
	nbConv    uint8
	heaterIdx uint8

	tFine int32
	cal   bme680Calibration
}

// NewBME680Minimal creates a BME680Minimal, reads the 28 trimming
// coefficients, and applies the default configuration (heater profile 0 at
// 320 °C / 150 ms).
//
// transport must be a configured I²C transport bound to the chip's 7-bit
// address (0x76 or 0x77).
func NewBME680Minimal(t transport.Transport) (*BME680Minimal, error) {
	cal, err := bme680ReadCalibration(t)
	if err != nil {
		return nil, err
	}
	d := &BME680Minimal{
		transport: t,
		osrsT:     BME680OSRSX1,
		osrsP:     BME680OSRSX1,
		osrsH:     BME680OSRSX1,
		filter:    BME680Filter0,
		mode:      BME680ModeSleep,
		ambientC:  bme680DefaultAmbientC,
		heatTemp:  bme680DefaultHeaterTempC,
		heatDur:   bme680DefaultHeaterDurMs,
		gasOn:     true,
		nbConv:    0,
		heaterIdx: 0,
		cal:       cal,
	}
	// ctrl_hum must be written before ctrl_meas for the value to latch.
	if err := d.writeReg(bme680RegCtrlHum, d.osrsH); err != nil {
		return nil, err
	}
	if err := d.writeReg(bme680RegCtrlMeas, (d.osrsT<<5)|(d.osrsP<<2)|0); err != nil {
		return nil, err
	}
	if err := d.writeReg(bme680RegConfig, 0); err != nil {
		return nil, err
	}
	if err := d.setupHeater(0); err != nil {
		return nil, err
	}
	// Enable gas conversion on profile 0.
	if err := d.writeReg(bme680RegCtrlGas1, (1<<4)|0); err != nil {
		return nil, err
	}
	return d, nil
}

// writeReg writes a single byte to a register.
func (d *BME680Minimal) writeReg(reg, val uint8) error {
	return d.transport.Write([]byte{reg, val})
}

// readReg8 reads a single byte from a register.
func (d *BME680Minimal) readReg8(reg uint8) (uint8, error) {
	b, err := d.transport.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// setupHeater configures the res_heat_x and gas_wait_x registers for the
// given profile index using the current heatTemp/heatDur/ambientC values.
func (d *BME680Minimal) setupHeater(index uint8) error {
	res := bme680CalcHeaterResistance(d.heatTemp, d.ambientC, d.cal)
	gw := bme680CalcGasWait(d.heatDur)
	if err := d.writeReg(bme680RegResHeat0+index, res); err != nil {
		return err
	}
	return d.writeReg(bme680RegGasWait0+index, gw)
}

// triggerAndRead triggers a forced TPHG cycle and returns the raw ADC values
// and gas status flags from one burst read of registers 0x1F..0x2B.
func (d *BME680Minimal) triggerAndRead() (uint32, uint32, uint16, uint16, uint8, bool, bool, error) {
	if err := d.writeReg(bme680RegCtrlHum, d.osrsH); err != nil {
		return 0, 0, 0, 0, 0, false, false, err
	}
	ctrl := (d.osrsT << 5) | (d.osrsP << 2) | BME680ModeForced
	if err := d.writeReg(bme680RegCtrlMeas, ctrl); err != nil {
		return 0, 0, 0, 0, 0, false, false, err
	}
	time.Sleep(bme680MeasTime)
	raw, err := d.transport.WriteRead([]byte{bme680RegPressMsb}, 13)
	if err != nil {
		return 0, 0, 0, 0, 0, false, false, err
	}
	pressAdc := uint32(raw[0])<<12 | uint32(raw[1])<<4 | uint32(raw[2])>>4
	tempAdc := uint32(raw[3])<<12 | uint32(raw[4])<<4 | uint32(raw[5])>>4
	humAdc := uint16(raw[6])<<8 | uint16(raw[7])
	gasAdc := uint16(raw[11])<<2 | uint16(raw[12])>>6
	gasRange := raw[12] & 0x0F
	gasValid := (raw[12]>>5)&1 == 1
	heatStab := (raw[12]>>4)&1 == 1
	return pressAdc, tempAdc, humAdc, gasAdc, gasRange, gasValid, heatStab, nil
}

// Temperature triggers a forced TPHG cycle and returns the calibrated
// temperature in degrees Celsius. Caches t_fine and updates the ambient
// temperature used for heater-resistance calculation.
//
// Returns temperature in °C.
func (d *BME680Minimal) Temperature() (float32, error) {
	_, adcT, _, _, _, _, _, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, t := bme680CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	d.ambientC = t
	return t, nil
}

// Pressure triggers a forced TPHG cycle and returns the calibrated pressure
// in hPa. Self-contained.
//
// Returns pressure in hPa.
func (d *BME680Minimal) Pressure() (float32, error) {
	adcP, adcT, _, _, _, _, _, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, _ := bme680CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	return bme680CompensatePressure(adcP, tFine, d.cal), nil
}

// Humidity triggers a forced TPHG cycle and returns the calibrated relative
// humidity in %RH. Self-contained.
//
// Returns humidity in %RH.
func (d *BME680Minimal) Humidity() (float32, error) {
	_, adcT, adcH, _, _, _, _, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, _ := bme680CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	return bme680CompensateHumidity(adcH, tFine, d.cal), nil
}

// GasResistance triggers a forced TPHG cycle and returns the calibrated
// gas-sensor resistance in Ohms. Returns NaN if the gas_valid_r or
// heat_stab_r bit is clear (heater did not stabilize within gas_wait_x, or
// the slot was a dummy).
//
// Returns gas resistance in Ohms, or NaN on invalid reading.
func (d *BME680Minimal) GasResistance() (float32, error) {
	_, adcT, _, adcG, gasRange, gasValid, heatStab, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, _ := bme680CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	if !gasValid || !heatStab {
		return float32(math.NaN()), nil
	}
	return bme680CompensateGas(adcG, gasRange, d.cal.rangeSwitchingError), nil
}

// BME680Full is the BME680 4-in-1 environmental driver — full interface.
// Extends BME680Minimal with configuration, multi-profile heater support,
// and read_all.
type BME680Full struct {
	*BME680Minimal
}

// NewBME680Full creates a BME680Full with the default configuration.
//
// transport must be a configured I²C transport bound to the chip's 7-bit
// address (0x76 or 0x77).
func NewBME680Full(t transport.Transport) (*BME680Full, error) {
	m, err := NewBME680Minimal(t)
	if err != nil {
		return nil, err
	}
	return &BME680Full{BME680Minimal: m}, nil
}

// Configure writes ctrl_hum, config, and ctrl_meas in the correct order.
//
// Parameters:
//   - osrsT  — temperature oversampling (0=skip, 1..5 = ×1..×16)
//   - osrsP  — pressure oversampling (same encoding)
//   - osrsH  — humidity oversampling (same encoding)
//   - mode   — power mode (BME680ModeSleep or BME680ModeForced; no normal mode)
//   - filter — IIR filter coefficient (0..7)
func (d *BME680Full) Configure(osrsT, osrsP, osrsH, mode, filter uint8) error {
	d.osrsT = osrsT
	d.osrsP = osrsP
	d.osrsH = osrsH
	d.mode = mode
	d.filter = filter
	if err := d.writeReg(bme680RegCtrlHum, osrsH); err != nil {
		return err
	}
	if err := d.writeReg(bme680RegConfig, filter<<2); err != nil {
		return err
	}
	return d.writeReg(bme680RegCtrlMeas, (osrsT<<5)|(osrsP<<2)|mode)
}

// SetOversampling updates all three TPH oversampling settings. The chip
// requires a write to ctrl_meas after every ctrl_hum change for it to
// latch, so ctrl_hum is always written first.
func (d *BME680Full) SetOversampling(osrsT, osrsP, osrsH uint8) error {
	d.osrsT = osrsT
	d.osrsP = osrsP
	d.osrsH = osrsH
	if err := d.writeReg(bme680RegCtrlHum, osrsH); err != nil {
		return err
	}
	return d.writeReg(bme680RegCtrlMeas, (osrsT<<5)|(osrsP<<2)|0)
}

// SetFilter updates the IIR filter coefficient in the config register. The
// IIR filter applies to temperature and pressure only (not humidity or gas).
func (d *BME680Full) SetFilter(coeff uint8) error {
	d.filter = coeff
	return d.writeReg(bme680RegConfig, coeff<<2)
}

// SetHeater configures profile 0 with the given heater target temperature
// (°C) and on-time (ms), and activates it. Re-applies using the current
// ambient temperature.
func (d *BME680Full) SetHeater(tempC int16, durationMs uint16) error {
	d.heatTemp = tempC
	d.heatDur = durationMs
	if err := d.setupHeater(0); err != nil {
		return err
	}
	return d.writeReg(bme680RegCtrlGas1, (1<<4)|0)
}

// SetHeaterProfile configures one of the 10 heater profiles without
// changing the active profile. Pass index 0–9, target temperature in °C,
// and on-time in ms.
func (d *BME680Full) SetHeaterProfile(index uint8, tempC int16, durationMs uint16) error {
	savedTemp, savedDur := d.heatTemp, d.heatDur
	d.heatTemp = tempC
	d.heatDur = durationMs
	if err := d.setupHeater(index); err != nil {
		d.heatTemp = savedTemp
		d.heatDur = savedDur
		return err
	}
	d.heatTemp = savedTemp
	d.heatDur = savedDur
	return nil
}

// SelectHeaterProfile selects which of the 10 heater profiles to use in
// the next forced cycle.
func (d *BME680Full) SelectHeaterProfile(index uint8) error {
	d.nbConv = index
	gas1 := uint8(0)
	if d.gasOn {
		gas1 = (1 << 4) | index
	} else {
		gas1 = index
	}
	return d.writeReg(bme680RegCtrlGas1, gas1)
}

// SetGasEnabled enables or disables gas conversion in the next forced cycle.
func (d *BME680Full) SetGasEnabled(enabled bool) error {
	d.gasOn = enabled
	gas1 := uint8(0)
	if enabled {
		gas1 = (1 << 4) | d.nbConv
	} else {
		gas1 = d.nbConv
	}
	return d.writeReg(bme680RegCtrlGas1, gas1)
}

// SetHeaterOff controls the heat_off override in ctrl_gas_0. true disables
// the heater; false enables normal heater operation.
func (d *BME680Full) SetHeaterOff(off bool) error {
	val := uint8(0)
	if off {
		val = 0x08
	}
	return d.writeReg(bme680RegCtrlGas0, val)
}

// SetAmbientTemperature overrides the ambient temperature used for
// heater-resistance calculation, and re-applies the active profile.
func (d *BME680Full) SetAmbientTemperature(tempC float32) error {
	d.ambientC = tempC
	return d.setupHeater(d.nbConv)
}

// ReadAll triggers one TPHG cycle and returns (temperature_C, pressure_hPa,
// humidity_RH, gas_resistance_Ohms). Gas resistance is NaN if the reading
// is invalid (heater did not stabilize).
func (d *BME680Full) ReadAll() (float32, float32, float32, float32, error) {
	pressAdc, adcT, adcH, adcG, gasRange, gasValid, heatStab, err := d.triggerAndRead()
	if err != nil {
		return 0, 0, 0, 0, err
	}
	tFine, t := bme680CompensateTemp(adcT, d.cal)
	d.tFine = tFine
	d.ambientC = t
	p := bme680CompensatePressure(pressAdc, tFine, d.cal)
	h := bme680CompensateHumidity(adcH, tFine, d.cal)
	var g float32
	if gasValid && heatStab {
		g = bme680CompensateGas(adcG, gasRange, d.cal.rangeSwitchingError)
	} else {
		g = float32(math.NaN())
	}
	return t, p, h, g, nil
}

// GasValid returns true if the most recent gas reading is real (gas_valid_r
// bit set in 0x2B).
func (d *BME680Full) GasValid() (bool, error) {
	b, err := d.readReg8(0x2B)
	if err != nil {
		return false, err
	}
	return (b>>5)&1 == 1, nil
}

// HeaterStable returns true if the most recent gas-conversion heater reached
// its target temperature within the gas_wait_x window (heat_stab_r bit in
// 0x2B).
func (d *BME680Full) HeaterStable() (bool, error) {
	b, err := d.readReg8(0x2B)
	if err != nil {
		return false, err
	}
	return (b>>4)&1 == 1, nil
}

// Status reads the measurement-status register at 0x1D. Bit 7 = new_data,
// bit 6 = gas_measuring, bit 5 = measuring, bits 3:0 = gas_meas_index.
func (d *BME680Full) Status() (uint8, error) {
	return d.readReg8(bme680RegMeasStatus)
}

// ChipID reads the chip ID register at 0xD0. Expect 0x61 for a BME680.
func (d *BME680Full) ChipID() (uint8, error) {
	return d.readReg8(bme680RegID)
}

// Reset performs a soft reset (write 0xB6 to 0xE0), re-reads the
// calibration coefficients, and re-applies the current configuration.
func (d *BME680Full) Reset() error {
	if err := d.writeReg(bme680RegReset, bme680ResetCmd); err != nil {
		return err
	}
	time.Sleep(2 * time.Millisecond)
	cal, err := bme680ReadCalibration(d.transport)
	if err != nil {
		return err
	}
	d.cal = cal
	if err := d.writeReg(bme680RegCtrlHum, d.osrsH); err != nil {
		return err
	}
	if err := d.writeReg(bme680RegConfig, d.filter<<2); err != nil {
		return err
	}
	if err := d.writeReg(bme680RegCtrlMeas, (d.osrsT<<5)|(d.osrsP<<2)|0); err != nil {
		return err
	}
	if err := d.setupHeater(d.nbConv); err != nil {
		return err
	}
	gas1 := uint8(0)
	if d.gasOn {
		gas1 = (1 << 4) | d.nbConv
	} else {
		gas1 = d.nbConv
	}
	return d.writeReg(bme680RegCtrlGas1, gas1)
}

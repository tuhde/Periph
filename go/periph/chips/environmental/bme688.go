// Package environmental contains drivers for combined environmental
// sensors (temperature, humidity, pressure).
package environmental

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BME688 register addresses (I²C; the chip has a flat address space on I²C).
const (
	bme688RegResHeatVal    = 0x00
	bme688RegResHeatRange  = 0x02
	bme688RegMeasStatus    = 0x1D
	bme688RegVariantID     = 0xF0
	bme688RegGasWaitShared = 0x6E
	bme688RegResHeat0      = 0x5A
	bme688RegGasWait0      = 0x64
	bme688RegCtrlGas0      = 0x70
	bme688RegCtrlGas1      = 0x71
	bme688RegCtrlHum       = 0x72
	bme688RegCtrlMeas      = 0x74
	bme688RegConfig        = 0x75
	bme688RegCalBlock1     = 0x8A
	bme688RegID            = 0xD0
	bme688RegReset         = 0xE0
	bme688RegCalBlock2     = 0xE1
)

// BME688 expected chip ID.
const bme688ChipID uint8 = 0x61

// BME688 expected variant ID (0x01 = BME688; tells it apart from the BME680).
const bme688VariantID uint8 = 0x01

// bme688RunGas is the run_gas bit (bit 5) of ctrl_gas_1; bme688FieldLen is the size of one data field.
const (
	bme688RunGas   uint8 = 0x20
	bme688FieldLen       = 17
)

// BME688 soft-reset command byte.
const bme688ResetCmd uint8 = 0xB6

// errBME688Timeout is returned when no measurement completed in time.
var errBME688Timeout = errors.New("bme688: measurement timeout")

// BME688 default heater target (datasheet recommendation) and duration.
const (
	bme688DefaultHeaterTempC = 320
	bme688DefaultHeaterDurMs = 150
	bme688DefaultAmbientC    = 25
)

// BME688 oversampling modes.
const (
	// BME688OSRSSkip disables the corresponding channel.
	BME688OSRSSkip uint8 = 0
	// BME688OSRSX1 is ×1 oversampling.
	BME688OSRSX1 uint8 = 1
	// BME688OSRSX2 is ×2 oversampling.
	BME688OSRSX2 uint8 = 2
	// BME688OSRSX4 is ×4 oversampling.
	BME688OSRSX4 uint8 = 3
	// BME688OSRSX8 is ×8 oversampling.
	BME688OSRSX8 uint8 = 4
	// BME688OSRSX16 is ×16 oversampling.
	BME688OSRSX16 uint8 = 5
)

// BME688 power modes (sleep, forced and parallel — no normal mode).
const (
	// BME688ModeSleep is the lowest-power mode; the chip idles.
	BME688ModeSleep uint8 = 0
	// BME688ModeForced triggers a single-shot measurement and returns to sleep.
	BME688ModeForced uint8 = 1
	// BME688ModeParallel cycles TPHG through the heater profile without host involvement.
	BME688ModeParallel uint8 = 2
)

// BME688 IIR filter coefficients (8 settings, applied to T and P only).
const (
	// BME688Filter0 disables the IIR filter.
	BME688Filter0 uint8 = 0
	// BME688Filter1 selects IIR coefficient 1.
	BME688Filter1 uint8 = 1
	// BME688Filter3 selects IIR coefficient 3.
	BME688Filter3 uint8 = 2
	// BME688Filter7 selects IIR coefficient 7.
	BME688Filter7 uint8 = 3
	// BME688Filter15 selects IIR coefficient 15.
	BME688Filter15 uint8 = 4
	// BME688Filter31 selects IIR coefficient 31.
	BME688Filter31 uint8 = 5
	// BME688Filter63 selects IIR coefficient 63.
	BME688Filter63 uint8 = 6
	// BME688Filter127 selects IIR coefficient 127.
	BME688Filter127 uint8 = 7
)

// BME688 status-register flags.
const (
	// BME688StatusNewData indicates new TPH data is available.
	BME688StatusNewData uint8 = 0x80
	// BME688StatusGasMeasuring indicates a gas conversion is in progress.
	BME688StatusGasMeasuring uint8 = 0x40
	// BME688StatusMeasuring indicates any conversion is in progress.
	BME688StatusMeasuring uint8 = 0x20
	// BME688StatusGasValid is set in gas_r_lsb when the gas reading is real.
	BME688StatusGasValid uint8 = 0x20
	// BME688StatusHeaterStable is set in gas_r_lsb when the heater reached target.
	BME688StatusHeaterStable uint8 = 0x10
)

// bme688Calibration holds all 28 factory trimming coefficients and the
// three single-byte calibration values for the BME688.
type bme688Calibration struct {
	parT1  uint16
	parT2  int16
	parT3  int8
	parP1  uint16
	parP2  int16
	parP3  int8
	parP4  int16
	parP5  int16
	parP6  int8
	parP7  int8
	parP8  int16
	parP9  int16
	parP10 uint8
	parH1  uint16
	parH2  uint16
	parH3  int8
	parH4  int8
	parH5  int8
	parH6  uint8
	parH7  int8
	parG1  int8
	parG2  int16
	parG3  int8

	resHeatVal   int8
	resHeatRange uint8
}

// bme688ReadCalibration reads the two non-contiguous calibration blocks plus
// the two single-byte calibration registers. Block 1 (0x8A..0xA0) holds the
// 23 bytes for the T and P coefficients (with the unusual P7-below-P6
// ordering). Block 2 (0xE1..0xEE) holds 14 bytes for the H, T1, and G
// coefficients — note that par_H1 and par_H2 share register 0xE2 (par_H2 in
// the high nibble, par_H1 in the low nibble).
func bme688ReadCalibration(t connection.RegisterConnection) (bme688Calibration, error) {
	var c bme688Calibration
	b1, err := t.ReadReg(bme688RegCalBlock1, 23)
	if err != nil {
		return c, err
	}
	b2, err := t.ReadReg(bme688RegCalBlock2, 14)
	if err != nil {
		return c, err
	}
	s1, err := t.ReadReg(bme688RegResHeatVal, 1)
	if err != nil {
		return c, err
	}
	s2, err := t.ReadReg(bme688RegResHeatRange, 1)
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
	return c, nil
}

// bme688CompensateTemp returns (t_fine, temperature_C).
func bme688CompensateTemp(adcT uint32, c bme688Calibration) (int32, float32) {
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

// bme688CompensatePressure returns pressure in hPa. 32-bit integer math.
func bme688CompensatePressure(adcP uint32, tFine int32, c bme688Calibration) float32 {
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

// bme688CompensateHumidity returns humidity in %RH. 32-bit integer math
// using 64-bit intermediates to avoid overflow on the temp-scaled
// multiplication.
func bme688CompensateHumidity(adcH uint16, tFine int32, c bme688Calibration) float32 {
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
		(((tempScaled * ((tempScaled * parH5) / 100)) >> 6) / 100) +
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

// bme688CompensateGas returns gas resistance in Ohms, or NaN on invalid
// inputs. The BME688 formula needs no lookup tables or calibration term.
func bme688CompensateGas(adcG uint16, gasRange uint8) float32 {
	var1 := int64(262144) >> gasRange
	var2 := int64(4096) + 3*(int64(adcG)-512)
	if var2 <= 0 {
		return float32(math.NaN())
	}
	return float32((10000 * var1 / var2) * 100)
}

// bme688CalcHeaterResistance computes the res_heat_x register value for a
// given target heater temperature and ambient temperature.
func bme688CalcHeaterResistance(targetTemp int16, ambientC float32, c bme688Calibration) uint8 {
	parG1 := int32(c.parG1)
	parG2 := int32(c.parG2)
	parG3 := int32(c.parG3)
	rhr := int32(c.resHeatRange)
	rhv := int32(c.resHeatVal)

	// Multiply as float before flooring to whole tenths, matching the spec's
	// `((amb_temp_c * par_G3) // 10) << 8` - truncating ambientC to int32
	// *before* the multiply (the previous implementation) silently discards
	// its fractional part and drifts from every other language's driver.
	var1 := int32(math.Floor(float64(ambientC)*float64(parG3)/10.0)) << 8
	var2 := (parG1 + 784) * ((((parG2+154009)*int32(targetTemp)*5)/100 + 3276800) / 10)
	var3 := var1 + (var2 >> 1)
	var4 := var3 / (rhr + 4)
	var5 := (131 * rhv) + 65536
	resHeatX100 := ((var4 / var5) - 250) * 34
	resHeatX := (resHeatX100 + 50) / 100
	return uint8(int32(0xFF) & resHeatX)
}

// bme688CalcGasWait encodes a target on-time in ms into the gas_wait_x
// format: 6-bit timer × 2-bit multiplier.
func bme688CalcGasWait(targetMs uint16) uint8 {
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

// BME688Minimal is the BME688 4-in-1 environmental driver — minimal interface.
//
// Default: forced mode, osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
// heater profile 0 at 320 °C / 150 ms with gas conversion enabled. Each
// call to Temperature, Pressure, Humidity, or GasResistance triggers a fresh
// forced-mode TPHG cycle, polls new_data and reads the 17-byte field
// (0x1D..0x2D) in one burst.
type BME688Minimal struct {
	connection connection.RegisterConnection

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
	cal   bme688Calibration
}

// NewBME688Minimal creates a BME688Minimal, reads the 28 trimming
// coefficients, and applies the default configuration (heater profile 0 at
// 320 °C / 150 ms).
//
// connection must be a configured I²C connection bound to the chip's 7-bit
// address (0x76 or 0x77).
func NewBME688Minimal(t connection.RegisterConnection) (*BME688Minimal, error) {
	cal, err := bme688ReadCalibration(t)
	if err != nil {
		return nil, err
	}
	d := &BME688Minimal{
		connection: t,
		osrsT:      BME688OSRSX1,
		osrsP:      BME688OSRSX1,
		osrsH:      BME688OSRSX1,
		filter:     BME688Filter0,
		mode:       BME688ModeSleep,
		ambientC:   bme688DefaultAmbientC,
		heatTemp:   bme688DefaultHeaterTempC,
		heatDur:    bme688DefaultHeaterDurMs,
		gasOn:      true,
		nbConv:     0,
		heaterIdx:  0,
		cal:        cal,
	}
	// ctrl_hum must be written before ctrl_meas for the value to latch.
	if err := d.connection.WriteReg(bme688RegCtrlHum, []byte{d.osrsH}); err != nil {
		return nil, err
	}
	if err := d.connection.WriteReg(bme688RegCtrlMeas, []byte{(d.osrsT << 5) | (d.osrsP << 2) | 0}); err != nil {
		return nil, err
	}
	if err := d.connection.WriteReg(bme688RegConfig, []byte{0}); err != nil {
		return nil, err
	}
	if err := d.setupHeater(0); err != nil {
		return nil, err
	}
	// Enable gas conversion on profile 0.
	if err := d.connection.WriteReg(bme688RegCtrlGas1, []byte{bme688RunGas | 0}); err != nil {
		return nil, err
	}
	return d, nil
}

// setupHeater configures the res_heat_x and gas_wait_x registers for the
// given profile index using the current heatTemp/heatDur/ambientC values.
func (d *BME688Minimal) setupHeater(index uint8) error {
	res := bme688CalcHeaterResistance(d.heatTemp, d.ambientC, d.cal)
	gw := bme688CalcGasWait(d.heatDur)
	if err := d.connection.WriteReg(bme688RegResHeat0+uint32(index), []byte{res}); err != nil {
		return err
	}
	return d.connection.WriteReg(bme688RegGasWait0+uint32(index), []byte{gw})
}

// bme688Field holds the raw values decoded from one 17-byte data field.
type bme688Field struct {
	pressAdc, tempAdc  uint32
	humAdc, gasAdc     uint16
	gasRange           uint8
	gasValid, heatStab bool
}

// bme688ParseField decodes the field starting at raw[off] (status byte first).
func bme688ParseField(raw []byte, off int) bme688Field {
	r := raw[off:]
	return bme688Field{
		pressAdc: uint32(r[2])<<12 | uint32(r[3])<<4 | uint32(r[4])>>4,
		tempAdc:  uint32(r[5])<<12 | uint32(r[6])<<4 | uint32(r[7])>>4,
		humAdc:   uint16(r[8])<<8 | uint16(r[9]),
		gasAdc:   uint16(r[15])<<2 | uint16(r[16])>>6,
		gasRange: r[16] & 0x0F,
		gasValid: (r[16]>>5)&1 == 1,
		heatStab: (r[16]>>4)&1 == 1,
	}
}

// triggerAndRead triggers a forced TPHG cycle, polls meas_status until
// new_data is set and measuring is clear (timeout = heater time + 500 ms),
// and returns the decoded field from one burst read of registers 0x1D..0x2D.
func (d *BME688Minimal) triggerAndRead() (bme688Field, error) {
	if err := d.connection.WriteReg(bme688RegCtrlHum, []byte{d.osrsH}); err != nil {
		return bme688Field{}, err
	}
	ctrl := (d.osrsT << 5) | (d.osrsP << 2) | BME688ModeForced
	if err := d.connection.WriteReg(bme688RegCtrlMeas, []byte{ctrl}); err != nil {
		return bme688Field{}, err
	}
	deadline := time.Now().Add(time.Duration(d.heatDur)*time.Millisecond + 500*time.Millisecond)
	for {
		st, err := d.connection.ReadReg(bme688RegMeasStatus, 1)
		if err != nil {
			return bme688Field{}, err
		}
		if st[0]&0x80 != 0 && st[0]&0x20 == 0 {
			break
		}
		if time.Now().After(deadline) {
			return bme688Field{}, errBME688Timeout
		}
		time.Sleep(5 * time.Millisecond)
	}
	raw, err := d.connection.ReadReg(bme688RegMeasStatus, bme688FieldLen)
	if err != nil {
		return bme688Field{}, err
	}
	return bme688ParseField(raw, 0), nil
}

// Temperature triggers a forced TPHG cycle and returns the calibrated
// temperature in degrees Celsius. Caches t_fine and updates the ambient
// temperature used for heater-resistance calculation.
//
// Returns temperature in °C.
func (d *BME688Minimal) Temperature() (float32, error) {
	f, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, t := bme688CompensateTemp(f.tempAdc, d.cal)
	d.tFine = tFine
	d.ambientC = t
	return t, nil
}

// Pressure triggers a forced TPHG cycle and returns the calibrated pressure
// in hPa. Self-contained.
//
// Returns pressure in hPa.
func (d *BME688Minimal) Pressure() (float32, error) {
	f, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, _ := bme688CompensateTemp(f.tempAdc, d.cal)
	d.tFine = tFine
	return bme688CompensatePressure(f.pressAdc, tFine, d.cal), nil
}

// Humidity triggers a forced TPHG cycle and returns the calibrated relative
// humidity in %RH. Self-contained.
//
// Returns humidity in %RH.
func (d *BME688Minimal) Humidity() (float32, error) {
	f, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, _ := bme688CompensateTemp(f.tempAdc, d.cal)
	d.tFine = tFine
	return bme688CompensateHumidity(f.humAdc, tFine, d.cal), nil
}

// GasResistance triggers a forced TPHG cycle and returns the calibrated
// gas-sensor resistance in Ohms. Returns NaN if the gas_valid_r or
// heat_stab_r bit is clear (heater did not stabilize within gas_wait_x, or
// the slot was a dummy).
//
// Returns gas resistance in Ohms, or NaN on invalid reading.
func (d *BME688Minimal) GasResistance() (float32, error) {
	f, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tFine, _ := bme688CompensateTemp(f.tempAdc, d.cal)
	d.tFine = tFine
	if !f.gasValid || !f.heatStab {
		return float32(math.NaN()), nil
	}
	return bme688CompensateGas(f.gasAdc, f.gasRange), nil
}

// BME688Full is the BME688 4-in-1 environmental driver — full interface.
// Extends BME688Minimal with configuration, multi-profile heater support,
// read_all and the parallel mode.
type BME688Full struct {
	*BME688Minimal
}

// NewBME688Full creates a BME688Full with the default configuration.
//
// connection must be a configured I²C connection bound to the chip's 7-bit
// address (0x76 or 0x77).
func NewBME688Full(t connection.RegisterConnection) (*BME688Full, error) {
	m, err := NewBME688Minimal(t)
	if err != nil {
		return nil, err
	}
	return &BME688Full{BME688Minimal: m}, nil
}

// Configure writes ctrl_hum, config, and ctrl_meas in the correct order.
//
// Parameters:
//   - osrsT  — temperature oversampling (0=skip, 1..5 = ×1..×16)
//   - osrsP  — pressure oversampling (same encoding)
//   - osrsH  — humidity oversampling (same encoding)
//   - mode   — power mode (BME688ModeSleep, BME688ModeForced or BME688ModeParallel)
//   - filter — IIR filter coefficient (0..7)
func (d *BME688Full) Configure(osrsT, osrsP, osrsH, mode, filter uint8) error {
	d.osrsT = osrsT
	d.osrsP = osrsP
	d.osrsH = osrsH
	d.mode = mode
	d.filter = filter
	if err := d.connection.WriteReg(bme688RegCtrlHum, []byte{osrsH}); err != nil {
		return err
	}
	if err := d.connection.WriteReg(bme688RegConfig, []byte{filter << 2}); err != nil {
		return err
	}
	return d.connection.WriteReg(bme688RegCtrlMeas, []byte{(osrsT << 5) | (osrsP << 2) | mode})
}

// SetOversampling updates all three TPH oversampling settings. The chip
// requires a write to ctrl_meas after every ctrl_hum change for it to
// latch, so ctrl_hum is always written first.
func (d *BME688Full) SetOversampling(osrsT, osrsP, osrsH uint8) error {
	d.osrsT = osrsT
	d.osrsP = osrsP
	d.osrsH = osrsH
	if err := d.connection.WriteReg(bme688RegCtrlHum, []byte{osrsH}); err != nil {
		return err
	}
	return d.connection.WriteReg(bme688RegCtrlMeas, []byte{(osrsT << 5) | (osrsP << 2) | 0})
}

// SetFilter updates the IIR filter coefficient in the config register. The
// IIR filter applies to temperature and pressure only (not humidity or gas).
func (d *BME688Full) SetFilter(coeff uint8) error {
	d.filter = coeff
	return d.connection.WriteReg(bme688RegConfig, []byte{coeff << 2})
}

// SetHeater configures profile 0 with the given heater target temperature
// (°C) and on-time (ms), and activates it. Re-applies using the current
// ambient temperature.
func (d *BME688Full) SetHeater(tempC int16, durationMs uint16) error {
	d.heatTemp = tempC
	d.heatDur = durationMs
	if err := d.setupHeater(0); err != nil {
		return err
	}
	return d.connection.WriteReg(bme688RegCtrlGas1, []byte{bme688RunGas | 0})
}

// SetHeaterProfile configures one of the 10 heater profiles without
// changing the active profile. Pass index 0–9, target temperature in °C,
// and on-time in ms.
func (d *BME688Full) SetHeaterProfile(index uint8, tempC int16, durationMs uint16) error {
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
func (d *BME688Full) SelectHeaterProfile(index uint8) error {
	d.nbConv = index
	gas1 := uint8(0)
	if d.gasOn {
		gas1 = bme688RunGas | index
	} else {
		gas1 = index
	}
	return d.connection.WriteReg(bme688RegCtrlGas1, []byte{gas1})
}

// SetGasEnabled enables or disables gas conversion in the next forced cycle.
func (d *BME688Full) SetGasEnabled(enabled bool) error {
	d.gasOn = enabled
	gas1 := uint8(0)
	if enabled {
		gas1 = bme688RunGas | d.nbConv
	} else {
		gas1 = d.nbConv
	}
	return d.connection.WriteReg(bme688RegCtrlGas1, []byte{gas1})
}

// SetHeaterOff controls the heat_off override in ctrl_gas_0. true disables
// the heater; false enables normal heater operation.
func (d *BME688Full) SetHeaterOff(off bool) error {
	val := uint8(0)
	if off {
		val = 0x08
	}
	return d.connection.WriteReg(bme688RegCtrlGas0, []byte{val})
}

// SetAmbientTemperature overrides the ambient temperature used for
// heater-resistance calculation, and re-applies the active profile.
func (d *BME688Full) SetAmbientTemperature(tempC float32) error {
	d.ambientC = tempC
	return d.setupHeater(d.nbConv)
}

// ReadAll triggers one TPHG cycle and returns (temperature_C, pressure_hPa,
// humidity_RH, gas_resistance_Ohms). Gas resistance is NaN if the reading
// is invalid (heater did not stabilize).
func (d *BME688Full) ReadAll() (float32, float32, float32, float32, error) {
	f, err := d.triggerAndRead()
	if err != nil {
		return 0, 0, 0, 0, err
	}
	tFine, t := bme688CompensateTemp(f.tempAdc, d.cal)
	d.tFine = tFine
	d.ambientC = t
	p := bme688CompensatePressure(f.pressAdc, tFine, d.cal)
	h := bme688CompensateHumidity(f.humAdc, tFine, d.cal)
	var g float32
	if f.gasValid && f.heatStab {
		g = bme688CompensateGas(f.gasAdc, f.gasRange)
	} else {
		g = float32(math.NaN())
	}
	return t, p, h, g, nil
}

// GasValid returns true if the most recent gas reading is real (gas_valid_r
// bit set in 0x2D).
func (d *BME688Full) GasValid() (bool, error) {
	r, err := d.connection.ReadReg(0x2D, 1)
	if err != nil {
		return false, err
	}
	return (r[0]>>5)&1 == 1, nil
}

// HeaterStable returns true if the most recent gas-conversion heater reached
// its target temperature within the gas_wait_x window (heat_stab_r bit in
// 0x2D).
func (d *BME688Full) HeaterStable() (bool, error) {
	r, err := d.connection.ReadReg(0x2D, 1)
	if err != nil {
		return false, err
	}
	return (r[0]>>4)&1 == 1, nil
}

// Status reads the measurement-status register at 0x1D. Bit 7 = new_data,
// bit 6 = gas_measuring, bit 5 = measuring, bits 3:0 = gas_meas_index.
func (d *BME688Full) Status() (uint8, error) {
	r, err := d.connection.ReadReg(bme688RegMeasStatus, 1)
	if err != nil {
		return 0, err
	}
	return r[0], nil
}

// ChipID reads the chip ID register at 0xD0. Expect 0x61 for a BME688.
func (d *BME688Full) ChipID() (uint8, error) {
	r, err := d.connection.ReadReg(bme688RegID, 1)
	if err != nil {
		return 0, err
	}
	return r[0], nil
}

// VariantID reads the variant ID register at 0xF0. Expect 0x01 for a BME688.
func (d *BME688Full) VariantID() (uint8, error) {
	r, err := d.connection.ReadReg(bme688RegVariantID, 1)
	if err != nil {
		return 0, err
	}
	return r[0], nil
}

// BME688Step is one heater step of the parallel-mode profile.
type BME688Step struct {
	// TempC is the heater target temperature in °C.
	TempC int16
	// Repeats is the number of TPHG sequences of this step (1–255).
	Repeats uint8
}

// BME688Field is one data field returned by ReadParallel.
type BME688Field struct {
	// Step is the heater step index (gas_meas_index).
	Step uint8
	// SubIndex is the sub_meas_index counter.
	SubIndex uint8
	// Temperature in °C.
	Temperature float32
	// Pressure in hPa.
	Pressure float32
	// Humidity in %RH.
	Humidity float32
	// GasResistance in Ω; NaN when the gas reading is not valid.
	GasResistance float32
	// GasValid is the gas_valid_r flag.
	GasValid bool
	// HeaterStable is the heat_stab_r flag.
	HeaterStable bool
}

func bme688CalcSharedWait(ms float32) uint8 {
	units := uint32(math.Floor(float64(ms)/0.477 + 0.5))
	switch {
	case units <= 0x3F:
		return uint8(units)
	case units <= 0x3F*4:
		return (1 << 6) | uint8(units/4)
	case units <= 0x3F*16:
		return (2 << 6) | uint8(units/16)
	}
	v := units / 64
	if v > 0x3F {
		v = 0x3F
	}
	return (3 << 6) | uint8(v)
}

// StartParallel starts parallel mode: the chip cycles TPHG through a heater
// profile on its own. It programs res_heat_x, gas_wait_x (repetitions),
// gas_wait_shared, nb_conv and run_gas, then sets mode = parallel. At least
// one of the T/P/H oversamplings must not be BME688OSRSSkip. steps holds
// 1–10 entries; sharedWaitMs is the wait before every TPHG sequence in ms
// (0.477 ms resolution).
func (d *BME688Full) StartParallel(steps []BME688Step, sharedWaitMs float32) error {
	if len(steps) < 1 || len(steps) > 10 {
		return errors.New("bme688: 1-10 heater steps required")
	}
	for i, st := range steps {
		rep := st.Repeats
		if rep == 0 {
			rep = 1
		}
		res := bme688CalcHeaterResistance(st.TempC, d.ambientC, d.cal)
		if err := d.connection.WriteReg(bme688RegResHeat0+uint32(i), []byte{res}); err != nil {
			return err
		}
		if err := d.connection.WriteReg(bme688RegGasWait0+uint32(i), []byte{rep}); err != nil {
			return err
		}
	}
	if err := d.connection.WriteReg(bme688RegGasWaitShared, []byte{bme688CalcSharedWait(sharedWaitMs)}); err != nil {
		return err
	}
	if err := d.connection.WriteReg(bme688RegCtrlGas1, []byte{bme688RunGas | uint8(len(steps))}); err != nil {
		return err
	}
	if err := d.connection.WriteReg(bme688RegCtrlHum, []byte{d.osrsH}); err != nil {
		return err
	}
	if err := d.connection.WriteReg(bme688RegConfig, []byte{d.filter << 2}); err != nil {
		return err
	}
	return d.connection.WriteReg(bme688RegCtrlMeas, []byte{(d.osrsT << 5) | (d.osrsP << 2) | BME688ModeParallel})
}

// ReadParallel burst-reads the three data fields and returns an entry for
// each field that has new_data set.
func (d *BME688Full) ReadParallel() ([]BME688Field, error) {
	raw, err := d.connection.ReadReg(bme688RegMeasStatus, 3*bme688FieldLen)
	if err != nil {
		return nil, err
	}
	var out []BME688Field
	for n := 0; n < 3; n++ {
		off := n * bme688FieldLen
		if raw[off]&0x80 == 0 {
			continue
		}
		f := bme688ParseField(raw, off)
		tFine, t := bme688CompensateTemp(f.tempAdc, d.cal)
		d.tFine = tFine
		g := float32(math.NaN())
		if f.gasValid && f.heatStab {
			g = bme688CompensateGas(f.gasAdc, f.gasRange)
		}
		out = append(out, BME688Field{
			Step:          raw[off] & 0x0F,
			SubIndex:      raw[off+1],
			Temperature:   t,
			Pressure:      bme688CompensatePressure(f.pressAdc, tFine, d.cal),
			Humidity:      bme688CompensateHumidity(f.humAdc, tFine, d.cal),
			GasResistance: g,
			GasValid:      f.gasValid,
			HeaterStable:  f.heatStab,
		})
	}
	return out, nil
}

// StopParallel leaves parallel mode (mode = sleep).
func (d *BME688Full) StopParallel() error {
	return d.connection.WriteReg(bme688RegCtrlMeas, []byte{(d.osrsT << 5) | (d.osrsP << 2) | BME688ModeSleep})
}

// Reset performs a soft reset (write 0xB6 to 0xE0), re-reads the
// calibration coefficients, and re-applies the current configuration.
func (d *BME688Full) Reset() error {
	if err := d.connection.WriteReg(bme688RegReset, []byte{bme688ResetCmd}); err != nil {
		return err
	}
	time.Sleep(2 * time.Millisecond)
	cal, err := bme688ReadCalibration(d.connection)
	if err != nil {
		return err
	}
	d.cal = cal
	if err := d.connection.WriteReg(bme688RegCtrlHum, []byte{d.osrsH}); err != nil {
		return err
	}
	if err := d.connection.WriteReg(bme688RegConfig, []byte{d.filter << 2}); err != nil {
		return err
	}
	if err := d.connection.WriteReg(bme688RegCtrlMeas, []byte{(d.osrsT << 5) | (d.osrsP << 2) | 0}); err != nil {
		return err
	}
	if err := d.setupHeater(d.nbConv); err != nil {
		return err
	}
	gas1 := uint8(0)
	if d.gasOn {
		gas1 = bme688RunGas | d.nbConv
	} else {
		gas1 = d.nbConv
	}
	return d.connection.WriteReg(bme688RegCtrlGas1, []byte{gas1})
}

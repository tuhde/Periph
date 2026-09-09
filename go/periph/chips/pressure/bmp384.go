// Package pressure contains drivers for standalone pressure sensors.
package pressure

import (
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BMP384 register addresses.
const (
	bmp384RegChipID   uint8 = 0x00
	bmp384RegStatus   uint8 = 0x03
	bmp384RegData0    uint8 = 0x04
	bmp384RegPwrCtrl  uint8 = 0x1B
	bmp384RegOSR      uint8 = 0x1C
	bmp384RegODR      uint8 = 0x1D
	bmp384RegConfig   uint8 = 0x1F
	bmp384RegCmd      uint8 = 0x7E
	bmp384RegCalStart uint8 = 0x31
	bmp384RegCalLen   uint8 = 21
)

// BMP384 expected chip ID.
const bmp384ChipID uint8 = 0x50

// BMP384 command bytes.
const (
	bmp384SoftResetCmd uint8 = 0xB6
	bmp384FIFOFlushCmd uint8 = 0xB0
)

// BMP384 power-mode bits in PWR_CTRL.
const (
	bmp384ModeSleep  uint8 = 0x00
	bmp384ModeForced uint8 = 0x01
	bmp384ModeNormal uint8 = 0x03
)

// BMP384 PWR_CTRL sensor enable bits.
const (
	bmp384PwrPressEn uint8 = 0x01
	bmp384PwrTempEn  uint8 = 0x02
)

// BMP384 FIFO frame header bytes.
const (
	bmp384FIFOHeaderPress   uint8 = 0x84
	bmp384FIFOHeaderTemp    uint8 = 0x90
	bmp384FIFOHeaderSensort uint8 = 0xA0
	bmp384FIFOHeaderError   uint8 = 0x44
	bmp384FIFOHeaderEmpty   uint8 = 0x80
)

const bmp384MeasTime = 40 * time.Millisecond

// One parsed FIFO frame. Type is one of "pressure", "temperature", "sensortime",
// "error", or "empty". For sensor frames, Value holds the engineering-units
// reading (hPa for pressure, °C for temperature, raw ticks for sensortime).
// For error and empty frames, Value is 0.
type BMP384FIFOFrame struct {
	Type  string
	Value float32
}

// bmp384Calibration holds the 13 factory trimming coefficients for the BMP384.
type bmp384Calibration struct {
	parT1, parT2, parT3     float64
	parP1, parP2, parP3     float64
	parP4, parP5, parP6     float64
	parP7, parP8, parP9     float64
	parP10, parP11          float64
}

// bmp384ReadCalibration reads the 21-byte NVM block (0x31..0x45) and unpacks
// all 13 trimming coefficients into floating-point PAR values per datasheet
// §"Calibration coefficient scaling".
func bmp384ReadCalibration(t connection.Connection) (bmp384Calibration, error) {
	var c bmp384Calibration
	buf, err := t.WriteRead([]byte{bmp384RegCalStart}, int(bmp384RegCalLen))
	if err != nil {
		return c, err
	}
	nvmT1 := uint16(buf[0]) | uint16(buf[1])<<8
	nvmT2 := uint16(buf[2]) | uint16(buf[3])<<8
	nvmT3 := int8(buf[4])
	nvmP1 := int16(uint16(buf[5]) | uint16(buf[6])<<8)
	nvmP2 := int16(uint16(buf[7]) | uint16(buf[8])<<8)
	nvmP3 := int8(buf[9])
	nvmP4 := int8(buf[10])
	nvmP5 := uint16(buf[11]) | uint16(buf[12])<<8
	nvmP6 := uint16(buf[13]) | uint16(buf[14])<<8
	nvmP7 := int8(buf[15])
	nvmP8 := int8(buf[16])
	nvmP9 := int16(uint16(buf[17]) | uint16(buf[18])<<8)
	nvmP10 := int8(buf[19])
	nvmP11 := int8(buf[20])

	c.parT1 = float64(nvmT1) * 256.0
	c.parT2 = float64(nvmT2) / math.Pow(2, 30)
	c.parT3 = float64(nvmT3) / math.Pow(2, 48)
	c.parP1 = (float64(nvmP1) - math.Pow(2, 14)) / math.Pow(2, 20)
	c.parP2 = (float64(nvmP2) - math.Pow(2, 14)) / math.Pow(2, 29)
	c.parP3 = float64(nvmP3) / math.Pow(2, 32)
	c.parP4 = float64(nvmP4) / math.Pow(2, 37)
	c.parP5 = float64(nvmP5) * 8.0
	c.parP6 = float64(nvmP6) / math.Pow(2, 6)
	c.parP7 = float64(nvmP7) / math.Pow(2, 8)
	c.parP8 = float64(nvmP8) / math.Pow(2, 15)
	c.parP9 = float64(nvmP9) / math.Pow(2, 48)
	c.parP10 = float64(nvmP10) / math.Pow(2, 48)
	c.parP11 = float64(nvmP11) / math.Pow(2, 65)
	return c, nil
}

// bmp384CompensateTemp returns t_lin and the temperature in °C.
func bmp384CompensateTemp(uncompT uint32, c bmp384Calibration) (float64, float64) {
	partial1 := float64(uncompT) - c.parT1
	partial2 := partial1 * c.parT2
	tLin := partial2 + (partial1 * partial1) * c.parT3
	return tLin, tLin
}

// bmp384CompensatePressure returns pressure in Pa. Uses caller-supplied t_lin.
func bmp384CompensatePressure(uncompP uint32, tLin float64, c bmp384Calibration) float64 {
	p := float64(uncompP)
	partial1 := c.parP6 * tLin
	partial2 := c.parP7 * tLin * tLin
	partial3 := c.parP8 * tLin * tLin * tLin
	partialOut1 := c.parP5 + partial1 + partial2 + partial3

	partial1 = c.parP2 * tLin
	partial2 = c.parP3 * tLin * tLin
	partial3 = c.parP4 * tLin * tLin * tLin
	partialOut2 := p * (c.parP1 + partial1 + partial2 + partial3)

	partial1 = p * p
	partial2 = c.parP9 + c.parP10 * tLin
	partial3 = partial1 * partial2
	partial4 := partial3 + (p * p * p) * c.parP11

	return partialOut1 + partialOut2 + partial4
}

// BMP384Minimal is the BMP384 combined pressure + temperature driver —
// minimal interface.
//
// Default: normal mode, osr_p=×16, osr_t=×2, iir=coef 3, ODR=25 Hz.
type BMP384Minimal struct {
	connection connection.Connection

	mode    uint8
	OsrP    uint8
	osrT    uint8
	Iir     uint8
	Odr     uint8
	tLin    float64
	cal     bmp384Calibration
}

// NewBMP384Minimal creates a BMP384Minimal, reads the 13 trimming
// coefficients, and applies the default configuration.
func NewBMP384Minimal(t connection.Connection) (*BMP384Minimal, error) {
	cal, err := bmp384ReadCalibration(t)
	if err != nil {
		return nil, err
	}
	d := &BMP384Minimal{
		connection: t,
		mode:       bmp384ModeNormal,
		OsrP:       4,    // ×16
		osrT:       1,    // ×2
		Iir:        2,    // coefficient 3
		Odr:        0x03, // 25 Hz
		cal:        cal,
	}
	if err := d.applyConfig(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *BMP384Minimal) writeReg(reg, val uint8) error {
	return d.connection.Write([]byte{reg, val})
}

func (d *BMP384Minimal) readReg(reg uint8, n int) ([]byte, error) {
	return d.connection.WriteRead([]byte{reg}, n)
}

func (d *BMP384Minimal) applyConfig() error {
	osrReg := (d.osrT << 3) | (d.OsrP << 0)
	configReg := d.Iir << 1
	pwrReg := (d.mode << 4) | bmp384PwrTempEn | bmp384PwrPressEn
	if err := d.writeReg(bmp384RegOSR, osrReg); err != nil {
		return err
	}
	if err := d.writeReg(bmp384RegConfig, configReg); err != nil {
		return err
	}
	if err := d.writeReg(bmp384RegODR, d.Odr); err != nil {
		return err
	}
	if err := d.writeReg(bmp384RegPwrCtrl, pwrReg); err != nil {
		return err
	}
	return nil
}

func (d *BMP384Minimal) applyPwr() error {
	pwrReg := (d.mode << 4) | bmp384PwrTempEn | bmp384PwrPressEn
	return d.writeReg(bmp384RegPwrCtrl, pwrReg)
}

func (d *BMP384Minimal) triggerAndRead() (uint32, uint32, error) {
	if d.mode == bmp384ModeForced {
		pwrReg := (bmp384ModeForced << 4) | bmp384PwrTempEn | bmp384PwrPressEn
		if err := d.writeReg(bmp384RegPwrCtrl, pwrReg); err != nil {
			return 0, 0, err
		}
		time.Sleep(bmp384MeasTime)
	}
	raw, err := d.readReg(bmp384RegData0, 6)
	if err != nil {
		return 0, 0, err
	}
	uncompP := uint32(raw[2])<<16 | uint32(raw[1])<<8 | uint32(raw[0])
	uncompT := uint32(raw[5])<<16 | uint32(raw[4])<<8 | uint32(raw[3])
	return uncompP, uncompT, nil
}

// Temperature triggers a fresh sample (when in forced mode) and returns the
// calibrated temperature in degrees Celsius.
//
// Returns temperature in °C.
func (d *BMP384Minimal) Temperature() (float32, error) {
	_, uncompT, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tLin, t := bmp384CompensateTemp(uncompT, d.cal)
	d.tLin = tLin
	return float32(t), nil
}

// Pressure triggers a fresh sample (when in forced mode) and returns the
// calibrated pressure in hPa. Always runs the temperature compensation first
// to populate t_lin (the pressure formula needs it).
//
// Returns pressure in hPa.
func (d *BMP384Minimal) Pressure() (float32, error) {
	uncompP, uncompT, err := d.triggerAndRead()
	if err != nil {
		return 0, err
	}
	tLin, _ := bmp384CompensateTemp(uncompT, d.cal)
	d.tLin = tLin
	return float32(bmp384CompensatePressure(uncompP, tLin, d.cal) / 100.0), nil
}

// BMP384Full is the BMP384 combined pressure + temperature driver — full
// interface. Extends BMP384Minimal with configuration, mode control, data-ready
// polling, soft reset, and FIFO support.
type BMP384Full struct {
	*BMP384Minimal
}

// NewBMP384Full creates a BMP384Full and applies the default configuration.
func NewBMP384Full(t connection.Connection) (*BMP384Full, error) {
	m, err := NewBMP384Minimal(t)
	if err != nil {
		return nil, err
	}
	return &BMP384Full{BMP384Minimal: m}, nil
}

// BMP384Mode / ODR / IIR constants exported as named values for callers.
const (
	BMP384ModeSleep  uint8 = 0x00
	BMP384ModeForced uint8 = 0x01
	BMP384ModeNormal uint8 = 0x03
)

// Configure writes OSR, CONFIG, and ODR.
//
// Parameters:
//   - osrP    — pressure oversampling (0=skip, 1..5 = ×1..×32)
//   - osrT    — temperature oversampling (same encoding)
//   - iirFilt — IIR filter coefficient index (0..7)
//   - odrSel  — output data rate selector (0x00..0x11)
func (d *BMP384Full) Configure(osrP, osrT, iirFilt, odrSel uint8) error {
	d.OsrP = osrP
	d.osrT = osrT
	d.Iir  = iirFilt
	d.Odr  = odrSel
	if err := d.writeReg(bmp384RegOSR, (osrT<<3)|(osrP<<0)); err != nil {
		return err
	}
	if err := d.writeReg(bmp384RegConfig, iirFilt<<1); err != nil {
		return err
	}
	return d.writeReg(bmp384RegODR, odrSel)
}

// SetMode updates the power-mode bits of PWR_CTRL.
func (d *BMP384Full) SetMode(mode uint8) error {
	d.mode = mode
	return d.applyPwr()
}

// IsDataReady reads STATUS and returns drdy_press (bit 5).
func (d *BMP384Full) IsDataReady() (bool, error) {
	buf, err := d.readReg(bmp384RegStatus, 1)
	if err != nil {
		return false, err
	}
	return (buf[0] & (1 << 5)) != 0, nil
}

// Softreset issues a soft reset (write 0xB6 to CMD), waits 2 ms, re-reads the
// calibration coefficients, and re-applies the current configuration.
func (d *BMP384Full) Softreset() error {
	if err := d.writeReg(bmp384RegCmd, bmp384SoftResetCmd); err != nil {
		return err
	}
	time.Sleep(3 * time.Millisecond)
	cal, err := bmp384ReadCalibration(d.connection)
	if err != nil {
		return err
	}
	d.cal = cal
	return d.applyConfig()
}

// FIFOConfig configures the FIFO source, watermark, and stop-on-full behaviour.
func (d *BMP384Full) FIFOConfig(pressEn, tempEn bool, wtm uint16, stopOnFull bool) error {
	cfg1 := uint8((1 << 4) |
		(boolToUint8(stopOnFull) << 3) |
		(boolToUint8(tempEn) << 1) |
		boolToUint8(pressEn))
	if err := d.writeReg(0x17, cfg1); err != nil {
		return err
	}
	if err := d.writeReg(0x15, uint8(wtm&0xFF)); err != nil {
		return err
	}
	return d.writeReg(0x16, uint8((wtm>>8)&0x01))
}

// FIFORead reads and parses every available FIFO frame.
func (d *BMP384Full) FIFORead() ([]BMP384FIFOFrame, error) {
	lenLo, err := d.readReg(0x12, 1)
	if err != nil {
		return nil, err
	}
	lenHi, err := d.readReg(0x13, 1)
	if err != nil {
		return nil, err
	}
	length := int(uint16(lenHi[0])<<8 | uint16(lenLo[0]))
	if length == 0 {
		return nil, nil
	}
	buf, err := d.readReg(0x14, length)
	if err != nil {
		return nil, err
	}
	var frames []BMP384FIFOFrame
	for i := 0; i < len(buf); {
		hdr := buf[i]
		switch hdr {
		case bmp384FIFOHeaderPress:
			if i+3 >= len(buf) {
				break
			}
			uncomp := uint32(buf[i+3])<<16 | uint32(buf[i+2])<<8 | uint32(buf[i+1])
			vPa := bmp384CompensatePressure(uncomp, d.tLin, d.cal)
			frames = append(frames, BMP384FIFOFrame{Type: "pressure", Value: float32(vPa / 100.0)})
			i += 4
		case bmp384FIFOHeaderTemp:
			if i+3 >= len(buf) {
				break
			}
			uncomp := uint32(buf[i+3])<<16 | uint32(buf[i+2])<<8 | uint32(buf[i+1])
			tLin, t := bmp384CompensateTemp(uncomp, d.cal)
			d.tLin = tLin
			frames = append(frames, BMP384FIFOFrame{Type: "temperature", Value: float32(t)})
			i += 4
		case bmp384FIFOHeaderSensort:
			if i+3 >= len(buf) {
				break
			}
			uncomp := uint32(buf[i+3])<<16 | uint32(buf[i+2])<<8 | uint32(buf[i+1])
			frames = append(frames, BMP384FIFOFrame{Type: "sensortime", Value: float32(uncomp)})
			i += 4
		case bmp384FIFOHeaderError:
			frames = append(frames, BMP384FIFOFrame{Type: "error", Value: 0})
			i += 1
		case bmp384FIFOHeaderEmpty:
			frames = append(frames, BMP384FIFOFrame{Type: "empty", Value: 0})
			i += 1
		default:
			frames = append(frames, BMP384FIFOFrame{Type: "unknown", Value: 0})
			i += 1
		}
	}
	return frames, nil
}

// FIFOFlush flushes all FIFO contents (write 0xB0 to CMD).
func (d *BMP384Full) FIFOFlush() error {
	return d.writeReg(bmp384RegCmd, bmp384FIFOFlushCmd)
}

// Altitude computes altitude above sea level from the current pressure using
// the international barometric formula.
//
// seaLevelHPa is the reference pressure (default 1013.25).
//
// Returns altitude in metres.
func (d *BMP384Full) Altitude(seaLevelHPa float32) (float32, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	if p <= 0 {
		return 0, nil
	}
	ratio := float64(p / seaLevelHPa)
	return float32(44330.0 * (1.0 - math.Pow(ratio, 1.0/5.255))), nil
}

// Read returns both pressure (hPa) and temperature (°C) from a single burst.
func (d *BMP384Full) Read() (float32, float32, error) {
	if d.mode == bmp384ModeForced {
		pwrReg := (bmp384ModeForced << 4) | bmp384PwrTempEn | bmp384PwrPressEn
		if err := d.writeReg(bmp384RegPwrCtrl, pwrReg); err != nil {
			return 0, 0, err
		}
	}
	raw, err := d.readReg(bmp384RegData0, 6)
	if err != nil {
		return 0, 0, err
	}
	uncompP := uint32(raw[2])<<16 | uint32(raw[1])<<8 | uint32(raw[0])
	uncompT := uint32(raw[5])<<16 | uint32(raw[4])<<8 | uint32(raw[3])
	tLin, t := bmp384CompensateTemp(uncompT, d.cal)
	d.tLin = tLin
	p := float32(bmp384CompensatePressure(uncompP, tLin, d.cal) / 100.0)
	return p, float32(t), nil
}

// ReadForced triggers a forced-mode measurement, waits T_conv, then returns
// both pressure (hPa) and temperature (°C).
func (d *BMP384Full) ReadForced() (float32, float32, error) {
	prevMode := d.mode
	if err := d.SetMode(BMP384ModeForced); err != nil {
		return 0, 0, err
	}
	tConvMs := bmp384ComputeTConvMs(d.OsrP, d.osrT)
	time.Sleep(time.Duration(tConvMs) * time.Millisecond)
	p, t, err := d.Read()
	d.mode = prevMode
	if err := d.applyPwr(); err != nil {
		return 0, 0, err
	}
	return p, t, err
}

func bmp384ComputeTConvMs(osrP, osrT uint8) uint32 {
	tConvUs := uint32(234 +
		392 + (1<<osrP)*2000 +
		313 + (1<<osrT)*2000)
	return tConvUs/1000 + 1
}

func boolToUint8(b bool) uint8 {
	if b {
		return 1
	}
	return 0
}

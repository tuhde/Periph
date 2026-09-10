// Package pressure contains drivers for standalone pressure sensors.
package pressure

import (
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LPS28DFW register addresses.
const (
	lps28dfwRegInterruptCfg uint8 = 0x0B
	lps28dfwRegWhoAmI       uint8 = 0x0F
	lps28dfwRegCtrlReg1     uint8 = 0x10
	lps28dfwRegCtrlReg2     uint8 = 0x11
	lps28dfwRegStatus       uint8 = 0x27
	lps28dfwRegPressOutXL   uint8 = 0x28
	lps28dfwRegPressOutL    uint8 = 0x29
	lps28dfwRegPressOutH    uint8 = 0x2A
	lps28dfwRegTempOutL     uint8 = 0x2B
	lps28dfwRegTempOutH     uint8 = 0x2C
	lps28dfwRegFIFODataXL   uint8 = 0x78
)

// LPS28DFW expected chip ID.
const lps28dfwChipID uint8 = 0xB4

// LPS28DFW boot wait time after power-up.
const lps28dfwBootWait = 2 * time.Millisecond

// LPS28DFW ODR selection.
const (
	LPS28DFWODRPowerDown uint8 = 0x00
	LPS28DFWODR1Hz       uint8 = 0x01
	LPS28DFWODR4Hz       uint8 = 0x02
	LPS28DFWODR10Hz      uint8 = 0x03
	LPS28DFWODR25Hz      uint8 = 0x04
	LPS28DFWODR50Hz      uint8 = 0x05
	LPS28DFWODR75Hz      uint8 = 0x06
	LPS28DFWODR100Hz     uint8 = 0x07
	LPS28DFWODR200Hz     uint8 = 0x08
)

// LPS28DFW averaging selection.
const (
	LPS28DFWAVG4   uint8 = 0x00
	LPS28DFWAVG8   uint8 = 0x01
	LPS28DFWAVG16  uint8 = 0x02
	LPS28DFWAVG32  uint8 = 0x03
	LPS28DFWAVG64  uint8 = 0x04
	LPS28DFWAVG128 uint8 = 0x05
	LPS28DFWAVG512 uint8 = 0x07
)

// LPS28DFW full-scale modes.
const (
	LPS28DFWFSMode1 uint8 = 0
	LPS28DFWFSMode2 uint8 = 1
)

// LPS28DFW IIR low-pass filter bandwidth.
const (
	LPS28DFWLFPFODROver4 uint8 = 0
	LPS28DFWLFPFODROver9 uint8 = 1
)

// LPS28DFW FIFO modes.
const (
	LPS28DFWFIFOBypass               uint8 = 0
	LPS28DFWFIFOFifo                 uint8 = 1
	LPS28DFWFIFOContinuous           uint8 = 2
	LPS28DFWFIFOBypassToFifo         uint8 = 4
	LPS28DFWFIFOBypassToContinuous   uint8 = 5
	LPS28DFWFIFOContinuousToFifo     uint8 = 6
)

// LPS28DFW status flags.
const (
	LPS28DFWStatusPDA uint8 = 0x01
	LPS28DFWStatusTDA uint8 = 0x02
	LPS28DFWStatusPOR uint8 = 0x10
	LPS28DFWStatusTOR uint8 = 0x20
)

// LPS28DFWMinimal is the LPS28DFW dual full-scale digital barometer — minimal interface.
//
// I²C address 0x5C (SA0=GND) or 0x5D (SA0=VDD). Factory calibration is applied
// in hardware; the 24-bit pressure output is already compensated.
//
// Default configuration (baked in at construction):
//   - FS_MODE = 0 (Mode 1, 0–1260 hPa, 4096 LSB/hPa)
//   - AVG = 0b010 (16 samples)
//   - ODR = 0b0100 (25 Hz)
//   - BDU = 1, EN_LPFP = 1, LFPF_CFG = 0 (ODR/4 bandwidth)
type LPS28DFWMinimal struct {
	connection connection.Connection

	fsMode uint8
	odr     uint8
	avg     uint8
	lpfEn   uint8
	lpfCfg  uint8
	bdu     uint8
}

// NewLPS28DFWMinimal creates an LPS28DFWMinimal and applies the default configuration.
func NewLPS28DFWMinimal(t connection.Connection) (*LPS28DFWMinimal, error) {
	d := &LPS28DFWMinimal{
		connection: t,
		fsMode:     0,
		odr:        0x04,
		avg:        0x02,
		lpfEn:      1,
		lpfCfg:     0,
		bdu:        1,
	}
	if err := d.writeReg(lps28dfwRegCtrlReg2, (d.fsMode<<6)|(d.lpfCfg<<5)|(d.lpfEn<<4)|(d.bdu<<3)); err != nil {
		return nil, err
	}
	if err := d.writeReg(lps28dfwRegCtrlReg1, (d.odr<<3)|(d.avg&0x07)); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *LPS28DFWMinimal) writeReg(reg, val uint8) error {
	return d.connection.Write([]byte{reg, val})
}

func (d *LPS28DFWMinimal) readReg8(reg uint8) (uint8, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *LPS28DFWMinimal) readReg16(reg uint8) (uint16, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0]) | uint16(b[1])<<8, nil
}

func (d *LPS28DFWMinimal) readPressureRaw() (int32, error) {
	b, err := d.connection.WriteRead([]byte{lps28dfwRegPressOutXL}, 3)
	if err != nil {
		return 0, err
	}
	v := int32(uint32(b[0]) | uint32(b[1])<<8 | uint32(b[2])<<16)
	if v&0x800000 != 0 {
		v |= -0x1000000
	}
	return v, nil
}

func (d *LPS28DFWMinimal) readTemperatureRaw() (int16, error) {
	v, err := d.readReg16(lps28dfwRegTempOutL)
	if err != nil {
		return 0, err
	}
	return int16(v), nil
}

// ReadPressure reads the absolute pressure in hPa. Sensitivity is 4096 LSB/hPa
// in Mode 1 (default) and 2048 LSB/hPa in Mode 2 (260–4060 hPa range).
//
// Returns pressure in hPa.
func (d *LPS28DFWMinimal) ReadPressure() (float32, error) {
	raw, err := d.readPressureRaw()
	if err != nil {
		return 0, err
	}
	sens := float32(4096.0)
	if d.fsMode == 1 {
		sens = 2048.0
	}
	return float32(float64(raw) / float64(sens)), nil
}

// ReadTemperature reads the sensor temperature in degrees Celsius (0.01 °C LSB).
//
// Returns temperature in °C.
func (d *LPS28DFWMinimal) ReadTemperature() (float32, error) {
	raw, err := d.readTemperatureRaw()
	if err != nil {
		return 0, err
	}
	return float32(float64(raw) / 100.0), nil
}

// LPS28DFWFull is the LPS28DFW dual full-scale digital barometer — full interface.
// Extends LPS28DFWMinimal with full configuration, FIFO, threshold, etc.
type LPS28DFWFull struct {
	*LPS28DFWMinimal
}

// NewLPS28DFWFull creates an LPS28DFWFull and applies the default configuration.
func NewLPS28DFWFull(t connection.Connection) (*LPS28DFWFull, error) {
	m, err := NewLPS28DFWMinimal(t)
	if err != nil {
		return nil, err
	}
	return &LPS28DFWFull{LPS28DFWMinimal: m}, nil
}

// Configure sets output data rate, averaging, full-scale mode, and IIR filter.
//
// Parameters:
//   - odr     — output data rate code (0=power-down, 1..8 = 1..200 Hz)
//   - avg     — averaging code (0..7; 0–5 for 4/8/16/32/64/128 samples, 7=512)
//   - fsMode  — 0=Mode 1 (0–1260 hPa), 1=Mode 2 (0–4060 hPa)
//   - lpfEn   — true to enable the IIR low-pass filter on pressure output
//   - lpfCfg  — 0=ODR/4 bandwidth, 1=ODR/9 bandwidth
func (d *LPS28DFWFull) Configure(odr, avg, fsMode, lpfCfg uint8, lpfEn bool) error {
	d.odr = odr
	d.avg = avg
	d.fsMode = fsMode
	if lpfEn {
		d.lpfEn = 1
	} else {
		d.lpfEn = 0
	}
	d.lpfCfg = lpfCfg
	ctrl2 := (d.fsMode << 6) | (d.lpfCfg << 5) | (d.lpfEn << 4) | (d.bdu << 3)
	if err := d.writeReg(lps28dfwRegCtrlReg2, ctrl2); err != nil {
		return err
	}
	ctrl1 := (d.odr << 3) | (d.avg & 0x07)
	return d.writeReg(lps28dfwRegCtrlReg1, ctrl1)
}

// Read burst-reads pressure and temperature.
func (d *LPS28DFWFull) Read() (float32, float32, error) {
	b, err := d.connection.WriteRead([]byte{lps28dfwRegPressOutXL}, 5)
	if err != nil {
		return 0, 0, err
	}
	p := int32(uint32(b[0]) | uint32(b[1])<<8 | uint32(b[2])<<16)
	if p&0x800000 != 0 {
		p |= -0x1000000
	}
	t := int16(uint16(b[3]) | uint16(b[4])<<8)
	sens := float32(4096.0)
	if d.fsMode == 1 {
		sens = 2048.0
	}
	return float32(float64(p) / float64(sens)), float32(float64(t) / 100.0), nil
}

// IsDataReady returns true if STATUS.P_DA is set.
func (d *LPS28DFWFull) IsDataReady() (bool, error) {
	s, err := d.readReg8(lps28dfwRegStatus)
	if err != nil {
		return false, err
	}
	return s&LPS28DFWStatusPDA != 0, nil
}

// ReadOneshot triggers a one-shot measurement (with ODR=0000) and returns the result.
func (d *LPS28DFWFull) ReadOneshot() (float32, float32, error) {
	saved, err := d.readReg8(lps28dfwRegCtrlReg1)
	if err != nil {
		return 0, 0, err
	}
	savedOdr := saved >> 3
	if err := d.writeReg(lps28dfwRegCtrlReg1, d.avg&0x07); err != nil {
		return 0, 0, err
	}
	c2, err := d.readReg8(lps28dfwRegCtrlReg2)
	if err != nil {
		return 0, 0, err
	}
	if err := d.writeReg(lps28dfwRegCtrlReg2, c2|0x01); err != nil {
		return 0, 0, err
	}
	for i := 0; i < 200; i++ {
		s, err := d.readReg8(lps28dfwRegStatus)
		if err != nil {
			return 0, 0, err
		}
		if s&LPS28DFWStatusPDA != 0 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	p, t, err := d.Read()
	if err != nil {
		return 0, 0, err
	}
	if err := d.writeReg(lps28dfwRegCtrlReg1, (savedOdr<<3)|(d.avg&0x07)); err != nil {
		return 0, 0, err
	}
	return p, t, nil
}

// SetOffset programs the one-point calibration offset (RPDS).
func (d *LPS28DFWFull) SetOffset(offsetHPa float32) error {
	sens := float32(4096.0)
	if d.fsMode == 1 {
		sens = 2048.0
	}
	raw := int32(offsetHPa * sens)
	if raw < 0 {
		raw += 0x10000
	}
	if err := d.writeReg(0x1A, uint8(raw&0xFF)); err != nil {
		return err
	}
	return d.writeReg(0x1B, uint8((raw>>8)&0xFF))
}

// Softreset issues a software reset.
func (d *LPS28DFWFull) Softreset() error {
	c2, err := d.readReg8(lps28dfwRegCtrlReg2)
	if err != nil {
		return err
	}
	if err := d.writeReg(lps28dfwRegCtrlReg2, c2|0x02); err != nil {
		return err
	}
	time.Sleep(lps28dfwBootWait)
	return nil
}

// FIFOConfigure configures FIFO mode, watermark level, and stop-on-watermark.
func (d *LPS28DFWFull) FIFOConfigure(mode, wtm uint8, stopOnWtm bool) error {
	if mode == LPS28DFWFIFOBypass {
		if err := d.writeReg(0x14, 0x00); err != nil {
			return err
		}
	}
	trig := uint8(0)
	if mode >= 4 {
		trig = 1
	}
	fMode := mode & 0x03
	stop := uint8(0)
	if stopOnWtm {
		stop = 1
	}
	ctrl := (trig << 2) | (stop << 3) | fMode
	if err := d.writeReg(0x14, ctrl); err != nil {
		return err
	}
	return d.writeReg(0x15, wtm&0x7F)
}

// FIFORead drains up to count pressure samples from the FIFO.
func (d *LPS28DFWFull) FIFORead(count uint8) ([]float32, error) {
	if count == 0 {
		return nil, nil
	}
	if count > 128 {
		count = 128
	}
	raw, err := d.connection.WriteRead([]byte{lps28dfwRegFIFODataXL}, int(count)*3)
	if err != nil {
		return nil, err
	}
	sens := float32(4096.0)
	if d.fsMode == 1 {
		sens = 2048.0
	}
	out := make([]float32, count)
	for i := 0; i < int(count); i++ {
		b := i * 3
		v := int32(uint32(raw[b]) | uint32(raw[b+1])<<8 | uint32(raw[b+2])<<16)
		if v&0x800000 != 0 {
			v |= -0x1000000
		}
		out[i] = float32(float64(v) / float64(sens))
	}
	return out, nil
}

// FIFOLevel returns the number of unread samples in the FIFO.
func (d *LPS28DFWFull) FIFOLevel() (uint8, error) {
	return d.readReg8(0x25)
}

// SetThreshold programs the pressure threshold and enables interrupt sources.
func (d *LPS28DFWFull) SetThreshold(thresholdHPa float32, high, low bool) error {
	sens := float32(16.0)
	if d.fsMode == 1 {
		sens = 8.0
	}
	raw := int32(thresholdHPa * sens)
	if raw < 0 {
		raw = 0
	}
	if raw > 0x7FFF {
		raw = 0x7FFF
	}
	if err := d.writeReg(0x0C, uint8(raw&0xFF)); err != nil {
		return err
	}
	if err := d.writeReg(0x0D, uint8((raw>>8)&0x7F)); err != nil {
		return err
	}
	cfg, err := d.readReg8(lps28dfwRegInterruptCfg)
	if err != nil {
		return err
	}
	v := cfg &^ 0x03
	if high {
		v |= 0x01
	}
	if low {
		v |= 0x02
	}
	return d.writeReg(lps28dfwRegInterruptCfg, v)
}

// ChipID reads the WHO_AM_I register.
func (d *LPS28DFWFull) ChipID() (uint8, error) {
	return d.readReg8(lps28dfwRegWhoAmI)
}

// Altitude computes altitude above sea level from the current pressure.
func (d *LPS28DFWFull) Altitude(seaLevelHPa float32) (float32, error) {
	p, err := d.ReadPressure()
	if err != nil {
		return 0, err
	}
	ratio := float64(p / seaLevelHPa)
	return float32(44330.0 * (1.0 - math.Pow(ratio, 1.0/5.255))), nil
}
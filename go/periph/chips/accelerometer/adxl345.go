// Package accelerometer contains drivers for standalone accelerometers.
package accelerometer

import (
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ADXL345 register addresses (6-bit, 0x00-0x39).
const (
	adxl345RegDevID         uint8 = 0x00
	adxl345RegThreshTap     uint8 = 0x1D
	adxl345RegOFSX          uint8 = 0x1E
	adxl345RegOFSY          uint8 = 0x1F
	adxl345RegOFSZ          uint8 = 0x20
	adxl345RegDUR           uint8 = 0x21
	adxl345RegLatent        uint8 = 0x22
	adxl345RegWindow        uint8 = 0x23
	adxl345RegThreshAct     uint8 = 0x24
	adxl345RegThreshInact   uint8 = 0x25
	adxl345RegTimeInact     uint8 = 0x26
	adxl345RegActInactCtl   uint8 = 0x27
	adxl345RegThreshFF      uint8 = 0x28
	adxl345RegTimeFF        uint8 = 0x29
	adxl345RegTapAxes       uint8 = 0x2A
	adxl345RegBWRate        uint8 = 0x2C
	adxl345RegPowerCtl      uint8 = 0x2D
	adxl345RegIntEnable     uint8 = 0x2E
	adxl345RegIntMap        uint8 = 0x2F
	adxl345RegIntSource     uint8 = 0x30
	adxl345RegDataFormat    uint8 = 0x31
	adxl345RegDataX0        uint8 = 0x32
	adxl345RegFifoCtl       uint8 = 0x38
	adxl345RegFifoStatus    uint8 = 0x39
)

// ADXL345 fixed device ID; reading any other value indicates a wrong device.
const adxl345DevIDValue uint8 = 0xE5

const (
	adxl345DataFormatDefault uint8 = 0x08 // FULL_RES=1, ±2 g
	adxl345BWRateDefault     uint8 = 0x0A // 100 Hz, normal power
	adxl345PowerCtlDefault   uint8 = 0x08 // Measure=1
)

// Full-resolution mode scale (g/LSB).
const adxl345FullResScaleGPerLSB float32 = 0.0039

// Interrupt source bits — match INT_ENABLE / INT_MAP / INT_SOURCE layout.
const (
	// ADXL345IntDataReady indicates a new data sample is available.
	ADXL345IntDataReady uint8 = 0x80
	// ADXL345IntSingleTap indicates a single-tap event.
	ADXL345IntSingleTap uint8 = 0x40
	// ADXL345IntDoubleTap indicates a double-tap event.
	ADXL345IntDoubleTap uint8 = 0x20
	// ADXL345IntActivity indicates an activity event.
	ADXL345IntActivity uint8 = 0x10
	// ADXL345IntInactivity indicates an inactivity event.
	ADXL345IntInactivity uint8 = 0x08
	// ADXL345IntFreeFall indicates a free-fall event.
	ADXL345IntFreeFall uint8 = 0x04
	// ADXL345IntWatermark indicates the FIFO has reached the watermark.
	ADXL345IntWatermark uint8 = 0x02
	// ADXL345IntOverrun indicates a FIFO overrun.
	ADXL345IntOverrun uint8 = 0x01
)

// FIFO mode values — match FIFO_CTL bits 7:6.
const (
	// ADXL345FifoBypass disables the FIFO.
	ADXL345FifoBypass uint8 = 0x00
	// ADXL345FifoFIFO retains samples until full, then discards oldest.
	ADXL345FifoFIFO uint8 = 0x40
	// ADXL345FifoStream retains samples until full, then discards newest.
	ADXL345FifoStream uint8 = 0x80
	// ADXL345FifoTrigger retains the last N samples then waits for trigger.
	ADXL345FifoTrigger uint8 = 0xC0
)

// Sleep-mode wakeup sample rates (POWER_CTL Wakeup bits 2:1).
const (
	// ADXL345Wakeup8HZ is the default sleep-mode sample rate.
	ADXL345Wakeup8HZ uint8 = 0x00
	// ADXL345Wakeup4HZ halves the sleep-mode sample rate.
	ADXL345Wakeup4HZ uint8 = 0x02
	// ADXL345Wakeup2HZ is one quarter of the default rate.
	ADXL345Wakeup2HZ uint8 = 0x04
	// ADXL345Wakeup1HZ is one eighth of the default rate.
	ADXL345Wakeup1HZ uint8 = 0x06
)

// adxl345RateCodes maps BW_RATE codes (Rate bits 3:0) to actual ODRs (Hz).
var adxl345RateCodes = []struct {
	code uint8
	rate float32
}{
	{0x0F, 3200},
	{0x0E, 1600},
	{0x0D, 800},
	{0x0C, 400},
	{0x0B, 200},
	{0x0A, 100},
	{0x09, 50},
	{0x08, 25},
	{0x07, 12.5},
	{0x06, 6.25},
}

func adxl345CmdByte(reg uint8, read, multi bool) uint8 {
	addr := reg & 0x3F
	if multi {
		addr |= 0x40
	}
	if read {
		addr |= 0x80
	}
	return addr
}

func adxl345EncodeOffset(offsetG float32) uint8 {
	raw := int32(math.Round(float64(offsetG / 0.0156)))
	if raw > 127 {
		raw = 127
	}
	if raw < -128 {
		raw = -128
	}
	return uint8(int8(raw))
}

// ADXL345Minimal is the ADXL345 3-axis accelerometer — minimal interface.
//
// Reads X, Y, Z acceleration in *g* with sensible defaults; no
// configuration is required beyond the connection. Supports I²C and SPI.
//
// Default configuration (baked in at construction):
// - Full-resolution mode (3.9 mg/LSB at any range)
// - ±2 g measurement range
// - 100 Hz output data rate, normal power
// - FIFO bypass, all interrupts disabled, no offsets
type ADXL345Minimal struct {
	conn       connection.Connection
	spi        bool
	rangeBits  uint8
	fullRes    bool
}

// NewADXL345Minimal creates an ADXL345Minimal, verifies DEVID, and applies
// defaults.
//
// connection must be a configured I²C or SPI connection bound to the
// chip (I²C address 0x53/0x1D, or an SPI chip-select). Pass spi=true for
// SPI — the driver prepends an R/W|MB|A5..A0 command byte to each
// transfer and sets MB=1 for multi-byte reads.
func NewADXL345Minimal(conn connection.Connection, spi bool) (*ADXL345Minimal, error) {
	c := &ADXL345Minimal{conn: conn, spi: spi, fullRes: true}
	if err := c.writeReg(adxl345RegDataFormat, adxl345DataFormatDefault); err != nil {
		return nil, err
	}
	if err := c.writeReg(adxl345RegBWRate, adxl345BWRateDefault); err != nil {
		return nil, err
	}
	if err := c.writeReg(adxl345RegPowerCtl, adxl345PowerCtlDefault); err != nil {
		return nil, err
	}
	devid, err := c.readReg8(adxl345RegDevID)
	if err != nil {
		return nil, err
	}
	if devid != adxl345DevIDValue {
		return nil, fmt.Errorf("ADXL345 DEVID: expected 0x%02X, got 0x%02X",
			adxl345DevIDValue, devid)
	}
	time.Sleep(11 * time.Millisecond)
	return c, nil
}

func (c *ADXL345Minimal) writeReg(reg, val uint8) error {
	if c.spi {
		cmd := adxl345CmdByte(reg, false, false)
		return c.conn.Write([]byte{cmd, val})
	}
	return c.conn.Write([]byte{reg, val})
}

func (c *ADXL345Minimal) readReg8(reg uint8) (uint8, error) {
	if c.spi {
		cmd := adxl345CmdByte(reg, true, false)
		b, err := c.conn.WriteRead([]byte{cmd}, 1)
		if err != nil {
			return 0, err
		}
		return b[0], nil
	}
	b, err := c.conn.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (c *ADXL345Minimal) readBurst(reg uint8, n int) ([]byte, error) {
	if c.spi {
		cmd := adxl345CmdByte(reg, true, n > 1)
		return c.conn.WriteRead([]byte{cmd}, n)
	}
	return c.conn.WriteRead([]byte{reg}, n)
}

// Read returns 3-axis linear acceleration as (x, y, z) in *g*.
//
// Reads all six data bytes (DATAX0..DATAZ1) in one burst so the X, Y, Z
// samples are guaranteed to come from a single measurement.
func (c *ADXL345Minimal) Read() (float32, float32, float32, error) {
	raw, err := c.readBurst(adxl345RegDataX0, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	rx := int16(uint16(raw[0]) | uint16(raw[1])<<8)
	ry := int16(uint16(raw[2]) | uint16(raw[3])<<8)
	rz := int16(uint16(raw[4]) | uint16(raw[5])<<8)
	scale := adxl345FullResScaleGPerLSB
	if !c.fullRes {
		scales := [4]float32{0.0039, 0.0078, 0.0156, 0.0312}
		scale = scales[c.rangeBits&0x03]
	}
	return float32(rx) * scale, float32(ry) * scale, float32(rz) * scale, nil
}

// ADXL345Full extends ADXL345Minimal with configuration, FIFO, tap /
// activity / inactivity / free-fall detection, and interrupt routing.
//
// Adds range and data-rate selection, low-power mode, self-test,
// per-axis offset calibration (in *g*), single/double-tap detection,
// activity and inactivity detection, free-fall detection, 32-level FIFO,
// interrupt routing (INT1 / INT2), and sleep / auto-sleep / link mode.
type ADXL345Full struct {
	*ADXL345Minimal
}

// NewADXL345Full creates an ADXL345Full.
func NewADXL345Full(conn connection.Connection, spi bool) (*ADXL345Full, error) {
	m, err := NewADXL345Minimal(conn, spi)
	if err != nil {
		return nil, err
	}
	return &ADXL345Full{ADXL345Minimal: m}, nil
}

// SetRange sets the measurement range to ±2/±4/±8/±16 g. FULL_RES is preserved
// so the scale factor stays 3.9 mg/LSB regardless of range.
func (c *ADXL345Full) SetRange(rangeG uint8) error {
	var code uint8
	switch rangeG {
	case 2:
		code = 0
	case 4:
		code = 1
	case 8:
		code = 2
	case 16:
		code = 3
	default:
		return nil
	}
	c.rangeBits = code
	df, err := c.readReg8(adxl345RegDataFormat)
	if err != nil {
		return err
	}
	df = (df &^ 0x03) | (code & 0x03)
	if c.fullRes {
		df |= 0x08
	}
	return c.writeReg(adxl345RegDataFormat, df)
}

// SetDataRate sets the output data rate to the nearest supported value
// (6.25 Hz–3200 Hz).
func (c *ADXL345Full) SetDataRate(rateHz float32) error {
	bestCode := adxl345RateCodes[0].code
	bestRate := adxl345RateCodes[0].rate
	bestDiff := float32(math.Abs(float64(bestRate - rateHz)))
	for _, rc := range adxl345RateCodes {
		if diff := float32(math.Abs(float64(rc.rate - rateHz))); diff < bestDiff {
			bestCode = rc.code
			bestRate = rc.rate
			bestDiff = diff
		}
	}
	bw, err := c.readReg8(adxl345RegBWRate)
	if err != nil {
		return err
	}
	bw = (bw &^ 0x0F) | (bestCode & 0x0F)
	return c.writeReg(adxl345RegBWRate, bw)
}

// SetLowPower enables or disables low-power mode (higher noise).
func (c *ADXL345Full) SetLowPower(enabled bool) error {
	bw, err := c.readReg8(adxl345RegBWRate)
	if err != nil {
		return err
	}
	if enabled {
		bw |= 0x10
	} else {
		bw &^= 0x10
	}
	return c.writeReg(adxl345RegBWRate, bw)
}

// SetOffset sets per-axis offset in *g*. The register scale is 15.6 mg/LSB
// and the signed value is clamped to ±2 *g* (≈ ±128 LSB).
func (c *ADXL345Full) SetOffset(x, y, z float32) error {
	if err := c.writeReg(adxl345RegOFSX, adxl345EncodeOffset(x)); err != nil {
		return err
	}
	if err := c.writeReg(adxl345RegOFSY, adxl345EncodeOffset(y)); err != nil {
		return err
	}
	return c.writeReg(adxl345RegOFSZ, adxl345EncodeOffset(z))
}

// CalibrateOffset measures and writes per-axis offsets to null sensor bias.
// Call with the sensor stationary and oriented so the Z axis points up
// (targetZ=1 *g*) for a typical gravity-referenced calibration.
func (c *ADXL345Full) CalibrateOffset(targetX, targetY, targetZ float32, samples int) error {
	var sx, sy, sz float32
	for i := 0; i < samples; i++ {
		x, y, z, err := c.Read()
		if err != nil {
			return err
		}
		sx += x
		sy += y
		sz += z
		time.Sleep(11 * time.Millisecond)
	}
	sx /= float32(samples)
	sy /= float32(samples)
	sz /= float32(samples)
	return c.SetOffset(targetX-sx, targetY-sy, targetZ-sz)
}

// SetTapDetection configures single-tap detection and enables the SINGLE_TAP
// interrupt.
//
//   - thresholdG: tap acceleration threshold in *g* (62.5 mg/LSB).
//   - durationMs: maximum tap duration in ms (625 µs/LSB).
//   - axes:       bitmask of participating axes (bit 2=X, 1=Y, 0=Z).
//   - suppress:   suppress double-tap if acceleration persists between taps.
func (c *ADXL345Full) SetTapDetection(thresholdG, durationMs float32, axes uint8, suppress bool) error {
	if err := c.writeReg(adxl345RegThreshTap, uint8(math.Round(float64(thresholdG/0.0625)))); err != nil {
		return err
	}
	if err := c.writeReg(adxl345RegDUR, uint8(math.Round(float64(durationMs/0.625)))); err != nil {
		return err
	}
	tapAxes := (axes & 0x07)
	if suppress {
		tapAxes |= 0x08
	}
	if err := c.writeReg(adxl345RegTapAxes, tapAxes); err != nil {
		return err
	}
	return c.enableInterrupt(ADXL345IntSingleTap)
}

// SetDoubleTap configures double-tap latency and window; enables DOUBLE_TAP
// interrupt.
func (c *ADXL345Full) SetDoubleTap(latencyMs, windowMs float32) error {
	if err := c.writeReg(adxl345RegLatent, uint8(math.Round(float64(latencyMs/1.25)))); err != nil {
		return err
	}
	if err := c.writeReg(adxl345RegWindow, uint8(math.Round(float64(windowMs/1.25)))); err != nil {
		return err
	}
	return c.enableInterrupt(ADXL345IntDoubleTap)
}

// SetActivity configures activity detection.
func (c *ADXL345Full) SetActivity(thresholdG float32, axes uint8, acCoupled bool) error {
	if err := c.writeReg(adxl345RegThreshAct, uint8(math.Round(float64(thresholdG/0.0625)))); err != nil {
		return err
	}
	aic, err := c.readReg8(adxl345RegActInactCtl)
	if err != nil {
		return err
	}
	aic &^= 0xF0
	if acCoupled {
		aic |= 0x80
	}
	aic |= axes & 0x70
	if err := c.writeReg(adxl345RegActInactCtl, aic); err != nil {
		return err
	}
	return c.enableInterrupt(ADXL345IntActivity)
}

// SetInactivity configures inactivity detection.
func (c *ADXL345Full) SetInactivity(thresholdG, timeSec float32, axes uint8, acCoupled bool) error {
	if err := c.writeReg(adxl345RegThreshInact, uint8(math.Round(float64(thresholdG/0.0625)))); err != nil {
		return err
	}
	if err := c.writeReg(adxl345RegTimeInact, uint8(math.Round(float64(timeSec)))); err != nil {
		return err
	}
	aic, err := c.readReg8(adxl345RegActInactCtl)
	if err != nil {
		return err
	}
	aic &^= 0x0F
	if acCoupled {
		aic |= 0x08
	}
	aic |= axes & 0x07
	if err := c.writeReg(adxl345RegActInactCtl, aic); err != nil {
		return err
	}
	return c.enableInterrupt(ADXL345IntInactivity)
}

// SetFreeFall configures free-fall detection and enables the FREE_FALL
// interrupt. Recommended threshold 0.3–0.6 g, time 100–350 ms.
func (c *ADXL345Full) SetFreeFall(thresholdG, timeMs float32) error {
	if err := c.writeReg(adxl345RegThreshFF, uint8(math.Round(float64(thresholdG/0.0625)))); err != nil {
		return err
	}
	if err := c.writeReg(adxl345RegTimeFF, uint8(math.Round(float64(timeMs/5.0)))); err != nil {
		return err
	}
	return c.enableInterrupt(ADXL345IntFreeFall)
}

// SetInterrupt enables or disables an interrupt source and routes it to
// INT1 (pin=1) or INT2 (pin=2).
func (c *ADXL345Full) SetInterrupt(source uint8, enabled bool, pin uint8) error {
	ie, err := c.readReg8(adxl345RegIntEnable)
	if err != nil {
		return err
	}
	im, err := c.readReg8(adxl345RegIntMap)
	if err != nil {
		return err
	}
	if enabled {
		ie |= source
		if pin == 2 {
			im |= source
		} else {
			im &^= source
		}
	} else {
		ie &^= source
	}
	if err := c.writeReg(adxl345RegIntEnable, ie); err != nil {
		return err
	}
	return c.writeReg(adxl345RegIntMap, im)
}

func (c *ADXL345Full) enableInterrupt(source uint8) error {
	return c.SetInterrupt(source, true, 1)
}

// ReadInterruptSource reads the INT_SOURCE register; clears latched
// interrupts.
func (c *ADXL345Full) ReadInterruptSource() (uint8, error) {
	return c.readReg8(adxl345RegIntSource)
}

// SetFifoMode configures the FIFO.
//
//   - mode:    one of ADXL345FifoBypass, ADXL345FifoFIFO, ADXL345FifoStream,
//     ADXL345FifoTrigger.
//   - samples: watermark level for FIFO/Stream, or retained-samples count
//     for Trigger.
func (c *ADXL345Full) SetFifoMode(mode uint8, samples uint8) error {
	fifoCtl := (mode & 0xC0) | (samples & 0x1F)
	return c.writeReg(adxl345RegFifoCtl, fifoCtl)
}

// FifoCount returns the number of FIFO entries currently available (0–32).
func (c *ADXL345Full) FifoCount() (uint8, error) {
	status, err := c.readReg8(adxl345RegFifoStatus)
	if err != nil {
		return 0, err
	}
	return status & 0x3F, nil
}

// ReadFifo drains the FIFO, returning up to maxSamples (x, y, z) tuples
// in *g*. Each sample is the same 6-byte burst as Read().
func (c *ADXL345Full) ReadFifo(maxSamples int) ([][3]float32, error) {
	n, err := c.FifoCount()
	if err != nil {
		return nil, err
	}
	if int(n) > maxSamples {
		n = uint8(maxSamples)
	}
	out := make([][3]float32, 0, n)
	for i := uint8(0); i < n; i++ {
		x, y, z, err := c.Read()
		if err != nil {
			return nil, err
		}
		out = append(out, [3]float32{x, y, z})
	}
	return out, nil
}

// SetSleep enters or leaves sleep mode.
//
//   - enabled: true to enter sleep, false to wake.
//   - wakeupHz: sleep-mode sample rate (8 / 4 / 2 / 1).
func (c *ADXL345Full) SetSleep(enabled bool, wakeupHz uint8) error {
	pwr, err := c.readReg8(adxl345RegPowerCtl)
	if err != nil {
		return err
	}
	if enabled {
		var wakeupCode uint8
		switch wakeupHz {
		case 8:
			wakeupCode = ADXL345Wakeup8HZ
		case 4:
			wakeupCode = ADXL345Wakeup4HZ
		case 2:
			wakeupCode = ADXL345Wakeup2HZ
		case 1:
			wakeupCode = ADXL345Wakeup1HZ
		default:
			return nil
		}
		pwr = (pwr &^ 0x06) | wakeupCode | 0x08
		pwr |= 0x04
	} else {
		pwr &^= 0x04
	}
	return c.writeReg(adxl345RegPowerCtl, pwr)
}

// SetLinkMode enables or disables the activity/inactivity serial-link mode.
func (c *ADXL345Full) SetLinkMode(enabled bool) error {
	pwr, err := c.readReg8(adxl345RegPowerCtl)
	if err != nil {
		return err
	}
	if enabled {
		pwr |= 0x40
	} else {
		pwr &^= 0x40
	}
	return c.writeReg(adxl345RegPowerCtl, pwr)
}

// SetAutoSleep enables or disables auto-sleep on inactivity (requires
// Link=1).
func (c *ADXL345Full) SetAutoSleep(enabled bool) error {
	pwr, err := c.readReg8(adxl345RegPowerCtl)
	if err != nil {
		return err
	}
	if enabled {
		pwr |= 0x20
	} else {
		pwr &^= 0x20
	}
	return c.writeReg(adxl345RegPowerCtl, pwr)
}

// SelfTest enables or disables the electrostatic self-test force on all
// axes.
func (c *ADXL345Full) SelfTest(enabled bool) error {
	df, err := c.readReg8(adxl345RegDataFormat)
	if err != nil {
		return err
	}
	if enabled {
		df |= 0x80
	} else {
		df &^= 0x80
	}
	return c.writeReg(adxl345RegDataFormat, df)
}
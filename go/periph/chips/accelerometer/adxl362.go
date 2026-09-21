// Package accelerometer contains drivers for standalone accelerometers.
package accelerometer

import (
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ADXL362 register addresses (6-bit, 0x00–0x3F).
const (
	adxl362RegDevIDAd       uint8 = 0x00
	adxl362RegDevIDMst      uint8 = 0x01
	adxl362RegPartID        uint8 = 0x02
	adxl362RegXData         uint8 = 0x08
	adxl362RegYData         uint8 = 0x09
	adxl362RegZData         uint8 = 0x0A
	adxl362RegStatus        uint8 = 0x0B
	adxl362RegFifoEntriesL  uint8 = 0x0C
	adxl362RegFifoEntriesH  uint8 = 0x0D
	adxl362RegXDataL        uint8 = 0x0E
	adxl362RegXDataH        uint8 = 0x0F
	adxl362RegYDataL        uint8 = 0x10
	adxl362RegYDataH        uint8 = 0x11
	adxl362RegZDataL        uint8 = 0x12
	adxl362RegZDataH        uint8 = 0x13
	adxl362RegTempL         uint8 = 0x14
	adxl362RegTempH         uint8 = 0x15
	adxl362RegSoftReset     uint8 = 0x1F
	adxl362RegThreshActL    uint8 = 0x20
	adxl362RegThreshActH    uint8 = 0x21
	adxl362RegTimeAct       uint8 = 0x22
	adxl362RegThreshInactL  uint8 = 0x23
	adxl362RegThreshInactH  uint8 = 0x24
	adxl362RegTimeInactL    uint8 = 0x25
	adxl362RegTimeInactH    uint8 = 0x26
	adxl362RegActInactCtl   uint8 = 0x27
	adxl362RegFifoControl   uint8 = 0x28
	adxl362RegFifoSamples   uint8 = 0x29
	adxl362RegIntMap1       uint8 = 0x2A
	adxl362RegIntMap2       uint8 = 0x2B
	adxl362RegFilterCtl     uint8 = 0x2C
	adxl362RegPowerCtl      uint8 = 0x2D
	adxl362RegSelfTest      uint8 = 0x2E
)

// ADXL362 expected identity bytes.
const (
	adxl362DevIDAdValue  uint8 = 0xAD
	adxl362DevIDMstValue uint8 = 0x1D
	adxl362PartIDValue   uint8 = 0xF2
)

// ADXL362 SPI command bytes.
const (
	adxl362CmdWriteReg uint8 = 0x0A
	adxl362CmdReadReg  uint8 = 0x0B
	adxl362CmdReadFifo uint8 = 0x0D
)

// ADXL362 SOFT_RESET key (ASCII 'R').
const adxl362SoftResetKey uint8 = 0x52

// FILTER_CTL reset value: RANGE=±2 g, HALF_BW=1, ODR=100 Hz.
const adxl362FilterCtlDefault uint8 = 0x13
// POWER_CTL measurement-mode value: MEASURE=10.
const adxl362PowerCtlMeasure uint8 = 0x02

// Per-range sensitivity (g/LSB), typical. The ±8 g value is intentionally
// not 4× the ±2 g value (4.255 mg/LSB vs. 1 mg/LSB).
var adxl362SensitivityGPerLSB = [3]float32{0.001, 0.002, 0.004255}

// STATUS bits.
const (
	adxl362StatusDataReady    uint8 = 0x01
	adxl362StatusFifoReady    uint8 = 0x02
	adxl362StatusFifoWatermark uint8 = 0x04
	adxl362StatusFifoOverrun  uint8 = 0x08
	adxl362StatusAct          uint8 = 0x10
	adxl362StatusInact        uint8 = 0x20
	adxl362StatusAwake        uint8 = 0x40
	adxl362StatusErrUserRegs  uint8 = 0x80
)

// Interrupt source constants (used by ADXL362Full.SetInterrupt).
const (
	// ADXL362SourceDataReady = STATUS.DATA_READY.
	ADXL362SourceDataReady uint8 = 0
	// ADXL362SourceFifoReady = STATUS.FIFO_READY.
	ADXL362SourceFifoReady uint8 = 1
	// ADXL362SourceFifoWatermark = STATUS.FIFO_WATERMARK.
	ADXL362SourceFifoWatermark uint8 = 2
	// ADXL362SourceFifoOverrun = STATUS.FIFO_OVERRUN.
	ADXL362SourceFifoOverrun uint8 = 3
	// ADXL362SourceAct = STATUS.ACT.
	ADXL362SourceAct uint8 = 4
	// ADXL362SourceInact = STATUS.INACT.
	ADXL362SourceInact uint8 = 5
	// ADXL362SourceAwake = STATUS.AWAKE.
	ADXL362SourceAwake uint8 = 6
)

// Noise mode constants (POWER_CTL.LOW_NOISE[5:4]).
const (
	// ADXL362NoiseNormal is the lowest-power mode (highest noise).
	ADXL362NoiseNormal uint8 = 0
	// ADXL362NoiseLow reduces noise at moderate power cost.
	ADXL362NoiseLow uint8 = 1
	// ADXL362NoiseUltralow is the lowest-noise mode (highest power cost).
	ADXL362NoiseUltralow uint8 = 2
)

// Link/loop mode constants (ACT_INACT_CTL.LINKLOOP[5:4]).
const (
	// ADXL362LinkLoopDefault requires the host to clear via STATUS read.
	ADXL362LinkLoopDefault uint8 = 0
	// ADXL362LinkLoopLinked links activity to inactivity.
	ADXL362LinkLoopLinked uint8 = 1
	// ADXL362LinkLoopLoop autonomously toggles activity/inactivity.
	ADXL362LinkLoopLoop uint8 = 3
)

// FIFO mode constants (FIFO_CONTROL.FIFO_MODE[1:0]).
const (
	// ADXL362FifoDisabled disables the FIFO.
	ADXL362FifoDisabled uint8 = 0
	// ADXL362FifoOldestSaved discards oldest entries on overflow.
	ADXL362FifoOldestSaved uint8 = 1
	// ADXL362FifoStream discards newest entries on overflow.
	ADXL362FifoStream uint8 = 2
	// ADXL362FifoTrigger waits for a trigger before saving.
	ADXL362FifoTrigger uint8 = 3
)

// FIFO entry-axis codes (top 2 bits of each 16-bit FIFO entry).
const (
	// ADXL362AxisX marks an X-axis FIFO entry.
	ADXL362AxisX uint8 = 0
	// ADXL362AxisY marks a Y-axis FIFO entry.
	ADXL362AxisY uint8 = 1
	// ADXL362AxisZ marks a Z-axis FIFO entry.
	ADXL362AxisZ uint8 = 2
	// ADXL362AxisTemp marks a temperature FIFO entry.
	ADXL362AxisTemp uint8 = 3
)

// adxl362ODRCodes maps FILTER_CTL.ODR[2:0] codes to actual ODRs (Hz).
var adxl362ODRCodes = []struct {
	code uint8
	rate float32
}{
	{0x00, 12.5},
	{0x01, 25.0},
	{0x02, 50.0},
	{0x03, 100.0},
	{0x04, 200.0},
	{0x05, 400.0},
	{0x06, 400.0},
	{0x07, 400.0},
}

// signExtend12 sign-extends a 12-bit two's-complement value to int32.
func signExtend12(v uint16) int32 {
	v &= 0x0FFF
	if v&0x0800 != 0 {
		return int32(int16(uint16(v) | 0xF000))
	}
	return int32(v)
}

// sleep is portable across Linux and TinyGo (no time.Sleep on TinyGo).
func sleep(ms int) {
	if ms <= 0 {
		return
	}
	time.Sleep(time.Duration(ms) * time.Millisecond)
}

// ADXL362Minimal reads X, Y, Z acceleration in *g* over SPI.
//
// Performs the chip's full power-up sequence at construction: verifies the
// DEVID triple, writes the reset FILTER_CTL and switches POWER_CTL into
// measurement mode.
type ADXL362Minimal struct {
	conn      connection.Connection
	rangeBits uint8  // RANGE field of FILTER_CTL (0, 0x40, 0x80/0xC0)
	odrHz     float32
}

// NewADXL362Minimal constructs an ADXL362Minimal and runs the chip's power-up
// sequence. Returns an error if the chip's DEVID triple does not match.
func NewADXL362Minimal(conn connection.Connection) (*ADXL362Minimal, error) {
	s := &ADXL362Minimal{conn: conn, rangeBits: 0x00, odrHz: 100.0}
	if err := s.Init(); err != nil {
		return nil, err
	}
	return s, nil
}

// Init re-runs the chip's full power-up sequence. Verifies the DEVID triple,
// writes FILTER_CTL and POWER_CTL, and waits the ODR turnaround.
func (s *ADXL362Minimal) Init() error {
	sleep(5)
	ids, err := s.readBurst(adxl362RegDevIDAd, 3)
	if err != nil {
		return fmt.Errorf("read DEVID: %w", err)
	}
	if ids[0] != adxl362DevIDAdValue {
		return fmt.Errorf("ADXL362 DEVID_AD: expected 0x%02X, got 0x%02X",
			adxl362DevIDAdValue, ids[0])
	}
	if ids[1] != adxl362DevIDMstValue {
		return fmt.Errorf("ADXL362 DEVID_MST: expected 0x%02X, got 0x%02X",
			adxl362DevIDMstValue, ids[1])
	}
	if ids[2] != adxl362PartIDValue {
		return fmt.Errorf("ADXL362 PARTID: expected 0x%02X, got 0x%02X",
			adxl362PartIDValue, ids[2])
	}
	if err := s.writeReg(adxl362RegFilterCtl, adxl362FilterCtlDefault); err != nil {
		return err
	}
	if err := s.writeReg(adxl362RegPowerCtl, adxl362PowerCtlMeasure); err != nil {
		return err
	}
	sleep(40)
	return nil
}

// Read returns the 3-axis linear acceleration in *g* as (x, y, z).
//
// Burst-reads the 12-bit XDATA_L/H, YDATA_L/H, ZDATA_L/H sextet so the
// X, Y, Z samples come from a single measurement.
func (s *ADXL362Minimal) Read() (float32, float32, float32, error) {
	raw, err := s.readBurst(adxl362RegXDataL, 6)
	if err != nil {
		return 0, 0, 0, fmt.Errorf("read 12-bit data: %w", err)
	}
	rx := signExtend12((uint16(raw[1]&0x0F) << 8) | uint16(raw[0]))
	ry := signExtend12((uint16(raw[3]&0x0F) << 8) | uint16(raw[2]))
	rz := signExtend12((uint16(raw[5]&0x0F) << 8) | uint16(raw[4]))
	sens := s.sensitivity()
	return float32(rx) * sens, float32(ry) * sens, float32(rz) * sens, nil
}

func (s *ADXL362Minimal) sensitivity() float32 {
	switch s.rangeBits {
	case 0x40:
		return adxl362SensitivityGPerLSB[1]
	case 0x80, 0xC0:
		return adxl362SensitivityGPerLSB[2]
	default:
		return adxl362SensitivityGPerLSB[0]
	}
}

func (s *ADXL362Minimal) writeReg(reg, value uint8) error {
	return s.conn.Write([]byte{adxl362CmdWriteReg, reg & 0x3F, value})
}

func (s *ADXL362Minimal) readReg(reg uint8) (uint8, error) {
	out, err := s.readBurst(reg, 1)
	if err != nil {
		return 0, err
	}
	return out[0], nil
}

func (s *ADXL362Minimal) readBurst(reg uint8, n int) ([]uint8, error) {
	return s.conn.WriteRead([]byte{adxl362CmdReadReg, reg & 0x3F}, n)
}

func (s *ADXL362Minimal) readFifo(n int) ([]uint8, error) {
	return s.conn.WriteRead([]byte{adxl362CmdReadFifo}, n)
}

func (s *ADXL362Minimal) readFifoEntries() (uint16, error) {
	lo, err := s.readReg(adxl362RegFifoEntriesL)
	if err != nil {
		return 0, err
	}
	hi, err := s.readReg(adxl362RegFifoEntriesH)
	if err != nil {
		return 0, err
	}
	return uint16(lo) | (uint16(hi&0x03) << 8), nil
}

// ADXL362Full extends ADXL362Minimal with the full chip API.
//
// Uses struct embedding to promote every Minimal method onto Full automatically
// — no delegate methods to write by hand, unlike Rust's composition-plus-forwarding.
type ADXL362Full struct {
	ADXL362Minimal
}

// NewADXL362Full constructs an ADXL362Full and runs the chip's power-up sequence.
func NewADXL362Full(conn connection.Connection) (*ADXL362Full, error) {
	min, err := NewADXL362Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &ADXL362Full{ADXL362Minimal: *min}, nil
}

// DeviceID returns raw device-ID bytes (DEVID_AD, DEVID_MST, PARTID, REVID).
func (f *ADXL362Full) DeviceID() (uint8, uint8, uint8, uint8, error) {
	ids, err := f.readBurst(adxl362RegDevIDAd, 4)
	if err != nil {
		return 0, 0, 0, 0, err
	}
	return ids[0], ids[1], ids[2], ids[3], nil
}

// SoftReset writes 0x52 to SOFT_RESET and waits the documented 0.5 ms latency.
// All registers return to reset defaults; caller must re-run Init.
func (f *ADXL362Full) SoftReset() error {
	if err := f.writeReg(adxl362RegSoftReset, adxl362SoftResetKey); err != nil {
		return err
	}
	sleep(1)
	f.rangeBits = 0x00
	f.odrHz = 100.0
	return nil
}

// SetRange sets the measurement range (2, 4, or 8 g).
func (f *ADXL362Full) SetRange(rangeG uint8) error {
	var code uint8
	switch rangeG {
	case 2:
		code = 0x00
	case 4:
		code = 0x40
	case 8:
		code = 0x80
	default:
		return nil
	}
	v, err := f.readReg(adxl362RegFilterCtl)
	if err != nil {
		return err
	}
	if err := f.writeReg(adxl362RegFilterCtl, (v&0x3F)|(code&0xC0)); err != nil {
		return err
	}
	f.rangeBits = code
	if f.odrHz > 0 {
		sleep(int(1000.0/f.odrHz + 1))
	}
	return nil
}

// SetODR sets the output data rate to the nearest supported value (12.5–400 Hz).
func (f *ADXL362Full) SetODR(odrHz float32) error {
	bestCode := adxl362ODRCodes[0].code
	bestRate := adxl362ODRCodes[0].rate
	bestDiff := float32(math.Abs(float64(bestRate - odrHz)))
	for _, c := range adxl362ODRCodes {
		d := float32(math.Abs(float64(c.rate - odrHz)))
		if d < bestDiff {
			bestCode, bestRate, bestDiff = c.code, c.rate, d
		}
	}
	v, err := f.readReg(adxl362RegFilterCtl)
	if err != nil {
		return err
	}
	if err := f.writeReg(adxl362RegFilterCtl, (v&0xF8)|(bestCode&0x07)); err != nil {
		return err
	}
	f.odrHz = bestRate
	return nil
}

// SetHalfBandwidth sets FILTER_CTL.HALF_BW.
func (f *ADXL362Full) SetHalfBandwidth(enabled bool) error {
	v, err := f.readReg(adxl362RegFilterCtl)
	if err != nil {
		return err
	}
	if enabled {
		v |= 0x10
	} else {
		v &^= 0x10
	}
	return f.writeReg(adxl362RegFilterCtl, v)
}

// SetNoiseMode sets POWER_CTL.LOW_NOISE (0=normal, 1=low, 2=ultralow noise).
func (f *ADXL362Full) SetNoiseMode(mode uint8) error {
	if mode > 2 {
		return fmt.Errorf("mode must be 0 (normal), 1 (low), or 2 (ultralow)")
	}
	v, err := f.readReg(adxl362RegPowerCtl)
	if err != nil {
		return err
	}
	return f.writeReg(adxl362RegPowerCtl, (v&0xCF)|((mode<<4)&0x30))
}

// SetWakeupMode sets POWER_CTL.WAKEUP (270 nA idle mode).
func (f *ADXL362Full) SetWakeupMode(enabled bool) error {
	v, err := f.readReg(adxl362RegPowerCtl)
	if err != nil {
		return err
	}
	if enabled {
		v |= 0x08
	} else {
		v &^= 0x08
	}
	return f.writeReg(adxl362RegPowerCtl, v)
}

// SetAutosleep sets POWER_CTL.AUTOSLEEP; effective only in linked/loop mode.
func (f *ADXL362Full) SetAutosleep(enabled bool) error {
	v, err := f.readReg(adxl362RegPowerCtl)
	if err != nil {
		return err
	}
	if enabled {
		v |= 0x04
	} else {
		v &^= 0x04
	}
	return f.writeReg(adxl362RegPowerCtl, v)
}

// SetExternalClock sets POWER_CTL.EXT_CLK; INT1 is repurposed as clock input.
func (f *ADXL362Full) SetExternalClock(enabled bool) error {
	v, err := f.readReg(adxl362RegPowerCtl)
	if err != nil {
		return err
	}
	if enabled {
		v |= 0x40
	} else {
		v &^= 0x40
	}
	return f.writeReg(adxl362RegPowerCtl, v)
}

// SetExternalSampleTrigger sets FILTER_CTL.EXT_SAMPLE; INT2 is repurposed as
// the external sync trigger input.
func (f *ADXL362Full) SetExternalSampleTrigger(enabled bool) error {
	v, err := f.readReg(adxl362RegFilterCtl)
	if err != nil {
		return err
	}
	if enabled {
		v |= 0x08
	} else {
		v &^= 0x08
	}
	return f.writeReg(adxl362RegFilterCtl, v)
}

// Read8bit returns the 3-axis acceleration using the 8-bit XDATA/YDATA/ZDATA
// registers.
func (f *ADXL362Full) Read8bit() (float32, float32, float32, error) {
	raw, err := f.readBurst(adxl362RegXData, 3)
	if err != nil {
		return 0, 0, 0, fmt.Errorf("read 8-bit data: %w", err)
	}
	s8 := func(v uint8) int32 {
		if v&0x80 != 0 {
			return int32(int8(v))
		}
		return int32(v)
	}
	sens := f.sensitivity() * 16
	return float32(s8(raw[0])) * sens, float32(s8(raw[1])) * sens, float32(s8(raw[2])) * sens, nil
}

// Temperature reads the on-chip temperature sensor using typical
// bias/sensitivity (350 LSB @ 25 °C, 0.065 °C/LSB).
func (f *ADXL362Full) Temperature() (float32, error) {
	raw, err := f.readBurst(adxl362RegTempL, 2)
	if err != nil {
		return 0, err
	}
	raw12 := signExtend12((uint16(raw[1]&0x0F) << 8) | uint16(raw[0]))
	return 25.0 + (float32(raw12)-350)*0.065, nil
}

// Status returns the raw STATUS register byte.
func (f *ADXL362Full) Status() (uint8, error) {
	return f.readReg(adxl362RegStatus)
}

// Awake returns STATUS.AWAKE.
func (f *ADXL362Full) Awake() (bool, error) {
	s, err := f.Status()
	if err != nil {
		return false, err
	}
	return s&adxl362StatusAwake != 0, nil
}

// DataReady returns STATUS.DATA_READY.
func (f *ADXL362Full) DataReady() (bool, error) {
	s, err := f.Status()
	if err != nil {
		return false, err
	}
	return s&adxl362StatusDataReady != 0, nil
}

// FifoEntries returns the 10-bit FIFO entry count (0–512).
func (f *ADXL362Full) FifoEntries() (uint16, error) {
	return f.readFifoEntries()
}

// ConfigureFifo configures the FIFO mode, optional temperature storage, and
// 9-bit watermark.
func (f *ADXL362Full) ConfigureFifo(mode uint8, storeTemp bool, watermark uint16) error {
	if mode > 3 {
		return fmt.Errorf("mode must be 0/1/2/3")
	}
	if watermark > 0x1FF {
		return fmt.Errorf("watermark must be 0–511")
	}
	fc := (mode & 0x03) | uint8((watermark>>8)&0x01)<<3
	if storeTemp {
		fc |= 0x04
	}
	if err := f.writeReg(adxl362RegFifoControl, fc); err != nil {
		return err
	}
	return f.writeReg(adxl362RegFifoSamples, uint8(watermark&0xFF))
}

// FifoEntry is a single FIFO entry.
type FifoEntry struct {
	Axis  uint8
	Value float32
}

// ReadFifo drains the FIFO and returns all available entries.
//
// Each 16-bit entry's top two bits encode the axis (0=X, 1=Y, 2=Z,
// 3=temperature); the low 12 bits are signed axis/temperature data.
func (f *ADXL362Full) ReadFifo() ([]FifoEntry, error) {
	n, err := f.readFifoEntries()
	if err != nil {
		return nil, err
	}
	if n == 0 {
		return nil, nil
	}
	raw, err := f.readFifo(int(n) * 2)
	if err != nil {
		return nil, err
	}
	sens := f.sensitivity()
	out := make([]FifoEntry, 0, n)
	for i := uint16(0); i < n; i++ {
		raw16 := (uint16(raw[2*i+1]) << 8) | uint16(raw[2*i])
		axis := (raw16 >> 14) & 0x03
		raw12 := signExtend12(raw16 & 0x0FFF)
		var val float32
		if axis == ADXL362AxisTemp {
			val = 25.0 + (float32(raw12)-350)*0.065
		} else {
			val = float32(raw12) * sens
		}
		out = append(out, FifoEntry{Axis: uint8(axis), Value: val})
	}
	return out, nil
}

// SetActivityThreshold sets the activity threshold (in *g*) and the
// referenced/absolute flag.
func (f *ADXL362Full) SetActivityThreshold(thresholdG float32, referenced bool) error {
	raw := int32(math.Round(float64(thresholdG / f.sensitivity())))
	if raw < 0 {
		raw = 0
	}
	if raw > 0x3FF {
		raw = 0x3FF
	}
	if err := f.writeReg(adxl362RegThreshActL, uint8(raw&0xFF)); err != nil {
		return err
	}
	if err := f.writeReg(adxl362RegThreshActH, uint8((raw>>8)&0x03)); err != nil {
		return err
	}
	aic, err := f.readReg(adxl362RegActInactCtl)
	if err != nil {
		return err
	}
	if referenced {
		aic |= 0x02
	} else {
		aic &^= 0x02
	}
	return f.writeReg(adxl362RegActInactCtl, aic)
}

// SetActivityTime sets the activity-time filter (0–255 samples).
func (f *ADXL362Full) SetActivityTime(samples uint8) error {
	return f.writeReg(adxl362RegTimeAct, samples)
}

// SetInactivityThreshold sets the inactivity threshold (in *g*) and the
// referenced/absolute flag.
func (f *ADXL362Full) SetInactivityThreshold(thresholdG float32, referenced bool) error {
	raw := int32(math.Round(float64(thresholdG / f.sensitivity())))
	if raw < 0 {
		raw = 0
	}
	if raw > 0x3FF {
		raw = 0x3FF
	}
	if err := f.writeReg(adxl362RegThreshInactL, uint8(raw&0xFF)); err != nil {
		return err
	}
	if err := f.writeReg(adxl362RegThreshInactH, uint8((raw>>8)&0x03)); err != nil {
		return err
	}
	aic, err := f.readReg(adxl362RegActInactCtl)
	if err != nil {
		return err
	}
	if referenced {
		aic |= 0x08
	} else {
		aic &^= 0x08
	}
	return f.writeReg(adxl362RegActInactCtl, aic)
}

// SetInactivityTime sets the inactivity-time filter (0–65535 samples).
func (f *ADXL362Full) SetInactivityTime(samples uint16) error {
	if err := f.writeReg(adxl362RegTimeInactL, uint8(samples&0xFF)); err != nil {
		return err
	}
	return f.writeReg(adxl362RegTimeInactH, uint8((samples>>8)&0xFF))
}

// EnableActivityDetection sets ACT_INACT_CTL.ACT_EN.
func (f *ADXL362Full) EnableActivityDetection(enabled bool) error {
	aic, err := f.readReg(adxl362RegActInactCtl)
	if err != nil {
		return err
	}
	if enabled {
		aic |= 0x01
	} else {
		aic &^= 0x01
	}
	return f.writeReg(adxl362RegActInactCtl, aic)
}

// EnableInactivityDetection sets ACT_INACT_CTL.INACT_EN.
func (f *ADXL362Full) EnableInactivityDetection(enabled bool) error {
	aic, err := f.readReg(adxl362RegActInactCtl)
	if err != nil {
		return err
	}
	if enabled {
		aic |= 0x04
	} else {
		aic &^= 0x04
	}
	return f.writeReg(adxl362RegActInactCtl, aic)
}

// SetLinkLoopMode sets ACT_INACT_CTL.LINKLOOP (0=default, 1=linked, 3=loop).
func (f *ADXL362Full) SetLinkLoopMode(mode uint8) error {
	if mode != 0 && mode != 1 && mode != 3 {
		return fmt.Errorf("mode must be 0 (default), 1 (linked), or 3 (loop)")
	}
	aic, err := f.readReg(adxl362RegActInactCtl)
	if err != nil {
		return err
	}
	return f.writeReg(adxl362RegActInactCtl, (aic&0xCF)|((mode<<4)&0x30))
}

func intmapBit(source uint8) uint8 {
	switch source {
	case ADXL362SourceDataReady:
		return 0x01
	case ADXL362SourceFifoReady:
		return 0x02
	case ADXL362SourceFifoWatermark:
		return 0x04
	case ADXL362SourceFifoOverrun:
		return 0x08
	case ADXL362SourceAct:
		return 0x10
	case ADXL362SourceInact:
		return 0x20
	case ADXL362SourceAwake:
		return 0x40
	default:
		return 0
	}
}

// SetInterrupt maps one interrupt source to the named INT pin (1 or 2).
func (f *ADXL362Full) SetInterrupt(pin, source uint8, enabled bool) error {
	if pin != 1 && pin != 2 {
		return fmt.Errorf("pin must be 1 or 2")
	}
	reg := adxl362RegIntMap1
	if pin == 2 {
		reg = adxl362RegIntMap2
	}
	cur, err := f.readReg(reg)
	if err != nil {
		return err
	}
	bit := intmapBit(source)
	if enabled {
		cur |= bit
	} else {
		cur &^= bit
	}
	return f.writeReg(reg, cur)
}

// SetInterruptPolarity sets the active-low polarity for one INT pin.
func (f *ADXL362Full) SetInterruptPolarity(pin uint8, activeLow bool) error {
	if pin != 1 && pin != 2 {
		return fmt.Errorf("pin must be 1 or 2")
	}
	reg := adxl362RegIntMap1
	if pin == 2 {
		reg = adxl362RegIntMap2
	}
	cur, err := f.readReg(reg)
	if err != nil {
		return err
	}
	if activeLow {
		cur |= 0x80
	} else {
		cur &^= 0x80
	}
	return f.writeReg(reg, cur)
}

// SelfTest enables or disables the electrostatic self-test force on all axes.
func (f *ADXL362Full) SelfTest(enabled bool) error {
	st, err := f.readReg(adxl362RegSelfTest)
	if err != nil {
		return err
	}
	if enabled {
		st |= 0x01
	} else {
		st &^= 0x01
	}
	if err := f.writeReg(adxl362RegSelfTest, st); err != nil {
		return err
	}
	if enabled && f.odrHz > 0 {
		sleep(int(4000.0/f.odrHz + 1))
	}
	return nil
}
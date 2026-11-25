package tof

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// VL6180X register addresses (16-bit).
const (
	vl6180xRegModelID              uint32 = 0x000
	vl6180xRegModelRevMajor        uint32 = 0x001
	vl6180xRegModelRevMinor        uint32 = 0x002
	vl6180xRegModeGpio1            uint32 = 0x011
	vl6180xRegInterruptConfig      uint32 = 0x014
	vl6180xRegInterruptClear       uint32 = 0x015
	vl6180xRegFreshOutOfReset      uint32 = 0x016
	vl6180xRegGroupedParameterHold uint32 = 0x017
	vl6180xRegRangeStart           uint32 = 0x018
	vl6180xRegRangeThreshHigh      uint32 = 0x019
	vl6180xRegRangeThreshLow       uint32 = 0x01A
	vl6180xRegRangeIntermeasure    uint32 = 0x01B
	vl6180xRegRangeMaxConvergence  uint32 = 0x01C
	vl6180xRegRangeCrosstalkRate   uint32 = 0x01E
	vl6180xRegRangeOffset          uint32 = 0x024
	vl6180xRegVhvRecalibrate       uint32 = 0x02E
	vl6180xRegVhvRepeatRate        uint32 = 0x031
	vl6180xRegAlsStart             uint32 = 0x038
	vl6180xRegAlsThreshHigh        uint32 = 0x03A
	vl6180xRegAlsThreshLow         uint32 = 0x03C
	vl6180xRegAlsIntermeasure      uint32 = 0x03E
	vl6180xRegAlsGain              uint32 = 0x03F
	vl6180xRegAlsIntegration       uint32 = 0x040
	vl6180xRegRangeStatus          uint32 = 0x04D
	vl6180xRegInterruptStatus      uint32 = 0x04F
	vl6180xRegAlsVal               uint32 = 0x050
	vl6180xRegHistoryBuffer        uint32 = 0x052
	vl6180xRegRangeVal             uint32 = 0x062
	vl6180xRegAveragingPeriod      uint32 = 0x10A
	vl6180xRegResultScaler         uint32 = 0x120
	vl6180xRegI2CAddress           uint32 = 0x212
	vl6180xRegInterleavedEnable    uint32 = 0x2A3
)

// VL6180XI2CAddress is the default 7-bit I2C address.
const VL6180XI2CAddress uint8 = 0x29

// VL6180XModelID is the expected IDENTIFICATION__MODEL_ID.
const VL6180XModelID uint8 = 0xB4

// Interrupt sources returned by PollInterrupt (bit mask) and accepted by
// EnableInterrupt / DisableInterrupt.
const (
	VL6180XSourceRange uint8 = 0x01 // range event
	VL6180XSourceALS   uint8 = 0x02 // ALS event
	VL6180XSourceError uint8 = 0x04 // error event
)

// Interrupt modes for EnableInterrupt.
const (
	VL6180XModeLevelLow       uint8 = 1 // value below the low threshold
	VL6180XModeLevelHigh      uint8 = 2 // value above the high threshold
	VL6180XModeOutOfWindow    uint8 = 3 // value outside the threshold window
	VL6180XModeNewSampleReady uint8 = 4 // new sample ready (default)
)

type vl6180xRegValue struct {
	reg   uint32
	value uint8
}

// vl6180xPrivateSettings are the mandatory private-register settings (ST
// AN4545), applied while SYSTEM__FRESH_OUT_OF_RESET = 1.
var vl6180xPrivateSettings = [30]vl6180xRegValue{
	{0x0207, 0x01},
	{0x0208, 0x01},
	{0x0096, 0x00},
	{0x0097, 0xFD},
	{0x00E3, 0x01},
	{0x00E4, 0x03},
	{0x00E5, 0x02},
	{0x00E6, 0x01},
	{0x00E7, 0x03},
	{0x00F5, 0x02},
	{0x00D9, 0x05},
	{0x00DB, 0xCE},
	{0x00DC, 0x03},
	{0x00DD, 0xF8},
	{0x009F, 0x00},
	{0x00A3, 0x3C},
	{0x00B7, 0x00},
	{0x00BB, 0x3C},
	{0x00B2, 0x09},
	{0x00CA, 0x09},
	{0x0198, 0x01},
	{0x01B0, 0x17},
	{0x01AD, 0x00},
	{0x00FF, 0x05},
	{0x0100, 0x05},
	{0x0199, 0x05},
	{0x01A6, 0x1B},
	{0x01AC, 0x3E},
	{0x01A7, 0x1F},
	{0x0030, 0x00},
}

// vl6180xRecommendedSettings are the recommended public settings (the
// 0x040 integration period is written as 16 bit separately).
var vl6180xRecommendedSettings = [8]vl6180xRegValue{
	{vl6180xRegModeGpio1, 0x10},
	{vl6180xRegAveragingPeriod, 0x30},
	{vl6180xRegAlsGain, 0x46},
	{vl6180xRegVhvRepeatRate, 0xFF},
	{vl6180xRegVhvRecalibrate, 0x01},
	{vl6180xRegRangeIntermeasure, 0x09},
	{vl6180xRegAlsIntermeasure, 0x31},
	{vl6180xRegInterruptConfig, 0x24},
}

// vl6180xGain is the actual ALS gain per gain code 0-7.
var vl6180xGain = [8]float32{20.0, 10.32, 5.21, 2.60, 1.72, 1.28, 1.01, 40.0}

const (
	vl6180xRangeTimeoutMs = 100
	vl6180xVhvTimeoutMs   = 100
)

var (
	// ErrVL6180XTimeout is returned when a poll loop expires.
	ErrVL6180XTimeout = errors.New("VL6180X: timeout")
	// ErrVL6180XInvalidArgument is returned for an argument outside its
	// documented range (no bus transaction made).
	ErrVL6180XInvalidArgument = errors.New("VL6180X: invalid argument")
	// ErrVL6180XRange is returned by ReadRange / ReadRangeContinuous when the
	// range error code is non-zero; use LastError for the code.
	ErrVL6180XRange = errors.New("VL6180X: range error")
)

// VL6180XMinimal is the VL6180X proximity and ambient-light sensor
// (STMicroelectronics) — minimal interface.
//
// VCSEL emitter, SPAD time-of-flight receiver and ambient light sensor (ALS)
// behind one aperture. Measures absolute distance 0-100 mm (8-bit result in
// mm) and ambient light (16-bit count, converted to lux). Registers use a
// 16-bit big-endian index; 16-bit results and thresholds are MSB first.
//
// The connection must be created with a register width of 2
// (NewI2CConnectionWithWidth) and point at address 0x29. The constructor runs
// the initialization sequence: 1 ms boot wait, then — only while
// SYSTEM__FRESH_OUT_OF_RESET is 1 — the mandatory private settings (ST
// AN4545, not documented in the datasheet), then the recommended settings
// (averaging period 48, max convergence 49 ms, ALS gain 1, 100 ms ALS
// integration, new-sample interrupt configuration). ReadRange then takes one
// single-shot measurement per call.
type VL6180XMinimal struct {
	conn          connection.RegisterConnection
	mu            sync.Mutex
	gainCode      uint8
	integrationMs uint16
	lastError     uint8

	callback    func(status uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewVL6180XMinimal creates a VL6180XMinimal and runs the initialization
// sequence.
func NewVL6180XMinimal(conn connection.RegisterConnection) (*VL6180XMinimal, error) {
	d := &VL6180XMinimal{conn: conn, gainCode: 6, integrationMs: 100}
	if err := d.init(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *VL6180XMinimal) rd8(reg uint32) (uint8, error) {
	b, err := d.conn.ReadReg(reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *VL6180XMinimal) wr8(reg uint32, value uint8) error {
	return d.conn.WriteReg(reg, []byte{value})
}

func (d *VL6180XMinimal) rd16(reg uint32) (uint16, error) {
	b, err := d.conn.ReadReg(reg, 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

func (d *VL6180XMinimal) wr16(reg uint32, value uint16) error {
	return d.conn.WriteReg(reg, []byte{uint8(value >> 8), uint8(value)})
}

func (d *VL6180XMinimal) init() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	time.Sleep(time.Millisecond)
	fresh, err := d.rd8(vl6180xRegFreshOutOfReset)
	if err != nil {
		return err
	}
	if fresh == 1 {
		for _, s := range vl6180xPrivateSettings {
			if err := d.wr8(s.reg, s.value); err != nil {
				return err
			}
		}
		if err := d.wr8(vl6180xRegFreshOutOfReset, 0); err != nil {
			return err
		}
	}
	for _, s := range vl6180xRecommendedSettings {
		if err := d.wr8(s.reg, s.value); err != nil {
			return err
		}
	}
	if err := d.wr16(vl6180xRegAlsIntegration, 0x0063); err != nil {
		return err
	}
	d.gainCode = 6
	d.integrationMs = 100
	return nil
}

// waitSample polls the interrupt status until (status >> shift) & 7 == 4, at
// most timeoutMs. Callers hold d.mu.
func (d *VL6180XMinimal) waitSample(shift uint, timeoutMs int) error {
	for i := 0; i <= timeoutMs; i++ {
		s, err := d.rd8(vl6180xRegInterruptStatus)
		if err != nil {
			return err
		}
		if (s>>shift)&0x07 == 4 {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrVL6180XTimeout
}

func (d *VL6180XMinimal) readRangeResult() (uint8, uint8, error) {
	st, err := d.rd8(vl6180xRegRangeStatus)
	if err != nil {
		return 0, 0, err
	}
	mm, err := d.rd8(vl6180xRegRangeVal)
	if err != nil {
		return 0, 0, err
	}
	if err := d.wr8(vl6180xRegInterruptClear, 0x07); err != nil {
		return 0, 0, err
	}
	d.lastError = st >> 4
	return mm, st >> 4, nil
}

func (d *VL6180XMinimal) measureRange() (uint8, uint8, error) {
	if err := d.wr8(vl6180xRegRangeStart, 0x01); err != nil {
		return 0, 0, err
	}
	if err := d.waitSample(0, vl6180xRangeTimeoutMs); err != nil {
		_ = d.wr8(vl6180xRegInterruptClear, 0x07)
		return 0, 0, err
	}
	return d.readRangeResult()
}

// ReadRange takes one single-shot range measurement and returns the distance
// in mm (0-255; reliable up to about 100 mm). It returns ErrVL6180XRange for
// a non-zero range error code (see LastError) and ErrVL6180XTimeout if no
// result arrives within 100 ms.
func (d *VL6180XMinimal) ReadRange() (uint8, error) {
	d.mu.Lock()
	mm, code, err := d.measureRange()
	d.mu.Unlock()
	if err != nil {
		return 0, err
	}
	if code != 0 {
		return mm, ErrVL6180XRange
	}
	return mm, nil
}

// LastError returns the range error code of the last measurement (0 = valid,
// 1-15 are the datasheet range error codes).
func (d *VL6180XMinimal) LastError() uint8 {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.lastError
}

// pollInterrupt reads the interrupt status and clears all pending sources.
func (d *VL6180XMinimal) pollInterrupt() (uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	s, err := d.rd8(vl6180xRegInterruptStatus)
	if err != nil {
		return 0, err
	}
	var sources uint8
	if s&0x07 != 0 {
		sources |= VL6180XSourceRange
	}
	if s&0x38 != 0 {
		sources |= VL6180XSourceALS
	}
	if s&0xC0 != 0 {
		sources |= VL6180XSourceError
	}
	if sources != 0 {
		if err := d.wr8(vl6180xRegInterruptClear, 0x07); err != nil {
			return 0, err
		}
	}
	return sources, nil
}

// VL6180XFull is the full VL6180X interface — extends VL6180XMinimal with ALS
// (lux), continuous and interleaved modes, thresholds, gain / integration /
// scaler, convergence and averaging, range offset and crosstalk, VHV
// recalibration, address change, identification, the history buffer and the
// Level-2 interrupt API.
type VL6180XFull struct {
	VL6180XMinimal
}

// NewVL6180XFull creates a VL6180XFull and runs the initialization sequence.
func NewVL6180XFull(conn connection.RegisterConnection) (*VL6180XFull, error) {
	d := &VL6180XFull{VL6180XMinimal: VL6180XMinimal{conn: conn, gainCode: 6, integrationMs: 100}}
	if err := d.init(); err != nil {
		return nil, err
	}
	return d, nil
}

// ReadRangeStatus takes one single-shot range measurement without failing on
// range errors. It returns the range in mm and the error code (0 = valid).
func (d *VL6180XFull) ReadRangeStatus() (uint8, uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.measureRange()
}

// StartContinuousRange starts continuous ranging; periodMs is 10-2550 in 10
// ms steps.
func (d *VL6180XFull) StartContinuousRange(periodMs uint16) error {
	if periodMs < 10 || periodMs > 2550 {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.wr8(vl6180xRegRangeIntermeasure, uint8(periodMs/10-1)); err != nil {
		return err
	}
	if err := d.wr8(vl6180xRegInterruptClear, 0x07); err != nil {
		return err
	}
	return d.wr8(vl6180xRegRangeStart, 0x03)
}

// StopContinuousRange stops continuous ranging (the start/stop bit toggles
// the running mode).
func (d *VL6180XFull) StopContinuousRange() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.wr8(vl6180xRegRangeStart, 0x01)
}

// ReadRangeContinuous waits for the next continuous-mode range sample and
// reads it. ErrVL6180XRange for a non-zero range error code,
// ErrVL6180XTimeout if no sample arrives within 100 ms plus one period.
func (d *VL6180XFull) ReadRangeContinuous() (uint8, error) {
	d.mu.Lock()
	period, err := d.rd8(vl6180xRegRangeIntermeasure)
	if err != nil {
		d.mu.Unlock()
		return 0, err
	}
	if err := d.waitSample(0, vl6180xRangeTimeoutMs+(int(period)+1)*10); err != nil {
		d.mu.Unlock()
		return 0, err
	}
	mm, code, err := d.readRangeResult()
	d.mu.Unlock()
	if err != nil {
		return 0, err
	}
	if code != 0 {
		return mm, ErrVL6180XRange
	}
	return mm, nil
}

// ReadAlsRaw takes one single-shot ALS measurement and returns the 16-bit
// count. ErrVL6180XTimeout if no result arrives within the integration time
// plus 100 ms.
func (d *VL6180XFull) ReadAlsRaw() (uint16, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.wr8(vl6180xRegAlsStart, 0x01); err != nil {
		return 0, err
	}
	if err := d.waitSample(3, int(d.integrationMs)+100); err != nil {
		_ = d.wr8(vl6180xRegInterruptClear, 0x07)
		return 0, err
	}
	raw, err := d.rd16(vl6180xRegAlsVal)
	if err != nil {
		return 0, err
	}
	if err := d.wr8(vl6180xRegInterruptClear, 0x07); err != nil {
		return 0, err
	}
	return raw, nil
}

// ReadLux takes one single-shot ALS measurement and converts it to lux:
// 0.32 * (count / gain) * (100 / integration_ms), with the actual gain of the
// current gain code (without cover glass).
func (d *VL6180XFull) ReadLux() (float32, error) {
	raw, err := d.ReadAlsRaw()
	if err != nil {
		return 0, err
	}
	d.mu.Lock()
	gain, integration := vl6180xGain[d.gainCode], d.integrationMs
	d.mu.Unlock()
	return 0.32 * (float32(raw) / gain) * (100.0 / float32(integration)), nil
}

// StartContinuousAls starts continuous ALS measurements; periodMs is 10-2550
// in 10 ms steps.
func (d *VL6180XFull) StartContinuousAls(periodMs uint16) error {
	if periodMs < 10 || periodMs > 2550 {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.wr8(vl6180xRegAlsIntermeasure, uint8(periodMs/10-1)); err != nil {
		return err
	}
	if err := d.wr8(vl6180xRegInterruptClear, 0x07); err != nil {
		return err
	}
	return d.wr8(vl6180xRegAlsStart, 0x03)
}

// StopContinuousAls stops continuous ALS measurements (the start/stop bit
// toggles the running mode).
func (d *VL6180XFull) StopContinuousAls() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.wr8(vl6180xRegAlsStart, 0x01)
}

// SetInterleaved enables or disables interleaved ALS + range mode (started
// with StartContinuousAls).
func (d *VL6180XFull) SetInterleaved(on bool) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	v := uint8(0)
	if on {
		v = 1
	}
	return d.wr8(vl6180xRegInterleavedEnable, v)
}

// held runs fn between writes of 1 and 0 to the grouped-parameter-hold register.
// Callers hold d.mu.
func (d *VL6180XFull) held(fn func() error) error {
	if err := d.wr8(vl6180xRegGroupedParameterHold, 1); err != nil {
		return err
	}
	if err := fn(); err != nil {
		return err
	}
	return d.wr8(vl6180xRegGroupedParameterHold, 0)
}

// SetAlsGain sets the ALS analogue gain code 0-7 (0 = 20, 1 = 10, 2 = 5,
// 3 = 2.5, 4 = 1.67, 5 = 1.25, 6 = 1, 7 = 40).
func (d *VL6180XFull) SetAlsGain(code uint8) error {
	if code > 7 {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.held(func() error { return d.wr8(vl6180xRegAlsGain, 0x40|code) }); err != nil {
		return err
	}
	d.gainCode = code
	return nil
}

// SetAlsIntegrationTime sets the ALS integration time, 1-512 ms.
func (d *VL6180XFull) SetAlsIntegrationTime(ms uint16) error {
	if ms < 1 || ms > 512 {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.held(func() error { return d.wr16(vl6180xRegAlsIntegration, ms-1) }); err != nil {
		return err
	}
	d.integrationMs = ms
	return nil
}

// SetAlsScaler sets the ALS result scaler (FIRMWARE__RESULT_SCALER), 1-16.
func (d *VL6180XFull) SetAlsScaler(scaler uint8) error {
	if scaler < 1 || scaler > 16 {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.wr8(vl6180xRegResultScaler, scaler)
}

// SetRangeThresholds sets the range interrupt thresholds in mm using the
// grouped-parameter-hold protocol; low must not exceed high.
func (d *VL6180XFull) SetRangeThresholds(low, high uint8) error {
	if low > high {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.held(func() error {
		if err := d.wr8(vl6180xRegRangeThreshHigh, high); err != nil {
			return err
		}
		return d.wr8(vl6180xRegRangeThreshLow, low)
	})
}

// SetAlsThresholds sets the ALS interrupt thresholds in counts using the
// grouped-parameter-hold protocol; low must not exceed high.
func (d *VL6180XFull) SetAlsThresholds(low, high uint16) error {
	if low > high {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.held(func() error {
		if err := d.wr16(vl6180xRegAlsThreshHigh, high); err != nil {
			return err
		}
		return d.wr16(vl6180xRegAlsThreshLow, low)
	})
}

// SetMaxConvergenceTime sets the maximum ranging convergence time, 1-63 ms.
func (d *VL6180XFull) SetMaxConvergenceTime(ms uint8) error {
	if ms < 1 || ms > 63 {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.wr8(vl6180xRegRangeMaxConvergence, ms)
}

// SetAveragingPeriod sets the ranging averaging sample period (about 64.5 us
// per unit; 48 is the default).
func (d *VL6180XFull) SetAveragingPeriod(units uint8) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.wr8(vl6180xRegAveragingPeriod, units)
}

// SetRangeOffset sets the part-to-part range offset in mm (two's complement
// on the chip).
func (d *VL6180XFull) SetRangeOffset(mm int8) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.wr8(vl6180xRegRangeOffset, uint8(mm))
}

// GetRangeOffset reads the part-to-part range offset in mm.
func (d *VL6180XFull) GetRangeOffset() (int8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	v, err := d.rd8(vl6180xRegRangeOffset)
	return int8(v), err
}

// SetCrosstalkRate sets the crosstalk compensation rate in Mcps (stored as
// 9.7 fixed point).
func (d *VL6180XFull) SetCrosstalkRate(rate float32) error {
	if !(rate >= 0) || rate*128+0.5 > 65535 {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.wr16(vl6180xRegRangeCrosstalkRate, uint16(rate*128+0.5))
}

// RecalibrateVHV starts a VHV recalibration and waits for the start bit to
// clear (100 ms timeout).
func (d *VL6180XFull) RecalibrateVHV() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.wr8(vl6180xRegVhvRecalibrate, 0x01); err != nil {
		return err
	}
	for i := 0; i <= vl6180xVhvTimeoutMs; i++ {
		v, err := d.rd8(vl6180xRegVhvRecalibrate)
		if err != nil {
			return err
		}
		if v&0x01 == 0 {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrVL6180XTimeout
}

// SetAddress changes the chip's I2C address (volatile, 0x08-0x77). The
// connection passed at construction still points at the old address; create
// a new connection and driver to continue.
func (d *VL6180XFull) SetAddress(address uint8) error {
	if address < 0x08 || address > 0x77 {
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.wr8(vl6180xRegI2CAddress, address&0x7F)
}

// ModelID reads the model ID (0xB4).
func (d *VL6180XFull) ModelID() (uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.rd8(vl6180xRegModelID)
}

// Revision reads the model revision (major, minor; bits 2:0 of each register).
func (d *VL6180XFull) Revision() (uint8, uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	major, err := d.rd8(vl6180xRegModelRevMajor)
	if err != nil {
		return 0, 0, err
	}
	minor, err := d.rd8(vl6180xRegModelRevMinor)
	if err != nil {
		return 0, 0, err
	}
	return major & 0x07, minor & 0x07, nil
}

// ReadHistory reads the eight-entry history buffer (enable it through the
// history control register first).
func (d *VL6180XFull) ReadHistory() ([8]uint16, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	var out [8]uint16
	b, err := d.conn.ReadReg(vl6180xRegHistoryBuffer, 16)
	if err != nil {
		return out, err
	}
	for i := range out {
		out[i] = uint16(b[2*i])<<8 | uint16(b[2*i+1])
	}
	return out, nil
}

func (d *VL6180XFull) setInterruptMode(source, mode uint8) error {
	var shift uint
	switch source {
	case VL6180XSourceRange:
		shift = 0
	case VL6180XSourceALS:
		shift = 3
	default:
		return ErrVL6180XInvalidArgument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	v, err := d.rd8(vl6180xRegInterruptConfig)
	if err != nil {
		return err
	}
	v = (v &^ (0x07 << shift)) | (mode << shift)
	return d.held(func() error { return d.wr8(vl6180xRegInterruptConfig, v) })
}

// EnableInterrupt selects the interrupt mode (VL6180XMode*, 1-4) of the range
// or ALS source (VL6180XSourceRange or VL6180XSourceALS).
func (d *VL6180XFull) EnableInterrupt(source, mode uint8) error {
	if mode < 1 || mode > 4 {
		return ErrVL6180XInvalidArgument
	}
	return d.setInterruptMode(source, mode)
}

// DisableInterrupt disables the interrupt of the range or ALS source (mode 0).
func (d *VL6180XFull) DisableInterrupt(source uint8) error {
	return d.setInterruptMode(source, 0)
}

// PollInterrupt reads the interrupt status and clears all pending sources.
// It returns a bit mask of VL6180XSourceRange, VL6180XSourceALS and
// VL6180XSourceError (0 if none).
func (d *VL6180XFull) PollInterrupt() (uint8, error) {
	return d.pollInterrupt()
}

// OnInterrupt subscribes callback to GPIO1 falling edges (active low) on the
// connection's IntPin, or to a 5 ms polling pin if the connection has none.
// Pending sources are cleared before the callback runs.
func (d *VL6180XFull) OnInterrupt(callback func(status uint8)) error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()

	pin := d.conn.IntPin()
	if pin == nil {
		p := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = p
		d.mu.Unlock()
		pin = p
	}
	unsub := pin.OnEdge(connection.Falling, func() {
		status, err := d.pollInterrupt()
		if err != nil || status == 0 {
			return
		}
		d.mu.Lock()
		cb := d.callback
		d.mu.Unlock()
		if cb != nil {
			cb(status)
		}
	})
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt detaches the edge handler, stops the polling pin and clears
// the callback.
func (d *VL6180XFull) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	d.callback = nil
	p := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if p != nil {
		return p.Close()
	}
	return nil
}

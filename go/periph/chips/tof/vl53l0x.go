// Package tof provides drivers for time-of-flight distance sensors.
package tof

import (
	"errors"
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// VL53L0X register addresses (ST API VL53L0X_REG_* names, prefix dropped).
const (
	vl53l0xRegSysrangeStart          uint8 = 0x00
	vl53l0xRegSystemSequenceConfig   uint8 = 0x01
	vl53l0xRegSystemIntermeasurement uint8 = 0x04
	vl53l0xRegSystemInterruptConfig  uint8 = 0x0A
	vl53l0xRegSystemInterruptClear   uint8 = 0x0B
	vl53l0xRegSystemThreshHigh       uint8 = 0x0C
	vl53l0xRegSystemThreshLow        uint8 = 0x0E
	vl53l0xRegResultInterruptStatus  uint8 = 0x13
	vl53l0xRegResultRangeStatus      uint8 = 0x14
	vl53l0xRegCrosstalkCompensation  uint8 = 0x20
	vl53l0xRegPartToPartRangeOffset  uint8 = 0x28
	vl53l0xRegPhasecalConfigTimeout  uint8 = 0x30
	vl53l0xRegGlobalConfigVcselWidth uint8 = 0x32
	vl53l0xRegFinalMinCountRateRtn   uint8 = 0x44
	vl53l0xRegMsrcConfigTimeout      uint8 = 0x46
	vl53l0xRegFinalValidPhaseLow     uint8 = 0x47
	vl53l0xRegFinalValidPhaseHigh    uint8 = 0x48
	vl53l0xRegDynamicSpadNumReq      uint8 = 0x4E
	vl53l0xRegDynamicSpadStartOffset uint8 = 0x4F
	vl53l0xRegPreRangeVcselPeriod    uint8 = 0x50
	vl53l0xRegPreRangeTimeout        uint8 = 0x51
	vl53l0xRegPreValidPhaseLow       uint8 = 0x56
	vl53l0xRegPreValidPhaseHigh      uint8 = 0x57
	vl53l0xRegMsrcConfigControl      uint8 = 0x60
	vl53l0xRegFinalRangeVcselPeriod  uint8 = 0x70
	vl53l0xRegFinalRangeTimeout      uint8 = 0x71
	vl53l0xRegPowerForce             uint8 = 0x80
	vl53l0xRegGpioHvMuxActiveHigh    uint8 = 0x84
	vl53l0xRegI2CMode                uint8 = 0x88
	vl53l0xRegVhvPadExtsupHv         uint8 = 0x89
	vl53l0xRegI2CSlaveDeviceAddress  uint8 = 0x8A
	vl53l0xRegStopVariable           uint8 = 0x91
	vl53l0xRegSpadEnablesRef0        uint8 = 0xB0
	vl53l0xRegRefEnStartSelect       uint8 = 0xB6
	vl53l0xRegModelID                uint8 = 0xC0
	vl53l0xRegRevisionID             uint8 = 0xC2
	vl53l0xRegOscCalibrateVal        uint8 = 0xF8
	vl53l0xRegPageSelect             uint8 = 0xFF
)

// SYSTEM_SEQUENCE_CONFIG step enables.
const (
	vl53l0xSeqTCC        uint8 = 0x10
	vl53l0xSeqDSS        uint8 = 0x08
	vl53l0xSeqMSRC       uint8 = 0x04
	vl53l0xSeqPreRange   uint8 = 0x40
	vl53l0xSeqFinalRange uint8 = 0x80
	vl53l0xSeqOperating  uint8 = 0xE8
)

const (
	vl53l0xTimeout           = 500 * time.Millisecond
	vl53l0xMinTimingBudgetUs = 20000

	// Timing-budget overheads, µs.
	vl53l0xStartOverhead      = 1910
	vl53l0xEndOverhead        = 960
	vl53l0xMsrcOverhead       = 660
	vl53l0xTccOverhead        = 590
	vl53l0xDssOverhead        = 690
	vl53l0xPreRangeOverhead   = 660
	vl53l0xFinalRangeOverhead = 550
)

// vl53l0xTuning is ST's DefaultTuningSettings — opaque, written verbatim in
// this order as (reg, value) pairs.
var vl53l0xTuning = [...]uint8{
	0xFF, 0x01, 0x00, 0x00, 0xFF, 0x00, 0x09, 0x00, 0x10, 0x00, 0x11, 0x00, 0x24, 0x01, 0x25, 0xFF, 0x75, 0x00,
	0xFF, 0x01, 0x4E, 0x2C, 0x48, 0x00, 0x30, 0x20, 0xFF, 0x00, 0x30, 0x09, 0x54, 0x00, 0x31, 0x04, 0x32, 0x03,
	0x40, 0x83, 0x46, 0x25, 0x60, 0x00, 0x27, 0x00, 0x50, 0x06, 0x51, 0x00, 0x52, 0x96, 0x56, 0x08, 0x57, 0x30,
	0x61, 0x00, 0x62, 0x00, 0x64, 0x00, 0x65, 0x00, 0x66, 0xA0, 0xFF, 0x01, 0x22, 0x32, 0x47, 0x14, 0x49, 0xFF,
	0x4A, 0x00, 0xFF, 0x00, 0x7A, 0x0A, 0x7B, 0x00, 0x78, 0x21, 0xFF, 0x01, 0x23, 0x34, 0x42, 0x00, 0x44, 0xFF,
	0x45, 0x26, 0x46, 0x05, 0x40, 0x40, 0x0E, 0x06, 0x20, 0x1A, 0x43, 0x40, 0xFF, 0x00, 0x34, 0x03, 0x35, 0x44,
	0xFF, 0x01, 0x31, 0x04, 0x4B, 0x09, 0x4C, 0x05, 0x4D, 0x04, 0xFF, 0x00, 0x44, 0x00, 0x45, 0x20, 0x47, 0x08,
	0x48, 0x28, 0x67, 0x00, 0x70, 0x04, 0x71, 0x01, 0x72, 0xFE, 0x76, 0x00, 0x77, 0x00, 0xFF, 0x01, 0x0D, 0x01,
	0xFF, 0x00, 0x80, 0x01, 0x01, 0xF8, 0xFF, 0x01, 0x8E, 0x01, 0x00, 0x01, 0xFF, 0x00, 0x80, 0x00,
}

// vl53l0xPrePhaseHigh is PRE_RANGE_CONFIG_VALID_PHASE_HIGH per pre-range
// VCSEL period (PCLKs).
var vl53l0xPrePhaseHigh = map[uint8]uint8{12: 0x18, 14: 0x30, 16: 0x40, 18: 0x50}

// vl53l0xFinalPhase is (VALID_PHASE_HIGH, VALID_PHASE_LOW, VCSEL_WIDTH,
// PHASECAL_CONFIG_TIMEOUT, page-1 PHASECAL_LIM) per final-range VCSEL period.
var vl53l0xFinalPhase = map[uint8][5]uint8{
	8:  {0x10, 0x08, 0x02, 0x0C, 0x30},
	10: {0x28, 0x08, 0x03, 0x09, 0x20},
	12: {0x38, 0x08, 0x03, 0x08, 0x20},
	14: {0x48, 0x08, 0x03, 0x07, 0x20},
}

// VL53L0XI2CAddress is the power-on 7-bit I²C address.
const VL53L0XI2CAddress uint8 = vl53I2CAddress

// VL53L0XModelID is the IDENTIFICATION_MODEL_ID value checked at
// construction.
const VL53L0XModelID uint8 = 0xEE

// VL53L0XRangeStatusValid is the device range status meaning "range complete
// — valid".
const VL53L0XRangeStatusValid uint8 = 11

// Interrupt sources — SYSTEM_INTERRUPT_CONFIG_GPIO values (mutually
// exclusive).
const (
	VL53L0XSourceLevelLow       = vl53SourceLevelLow       // range < low threshold
	VL53L0XSourceLevelHigh      = vl53SourceLevelHigh      // range > high threshold
	VL53L0XSourceOutOfWindow    = vl53SourceOutOfWindow    // range < low or > high threshold
	VL53L0XSourceNewSampleReady = vl53SourceNewSampleReady // new measurement available (driver default)
)

// VL53L0XVcselPeriodType selects a VCSEL pulse period.
type VL53L0XVcselPeriodType uint8

// VL53L0XVcselPeriodType values.
const (
	VL53L0XPreRange   VL53L0XVcselPeriodType = 0 // pre-range: 12, 14, 16 or 18 PCLKs
	VL53L0XFinalRange VL53L0XVcselPeriodType = 1 // final-range: 8, 10, 12 or 14 PCLKs
)

// VL53L0XProfile selects a ranging profile for SetProfile.
type VL53L0XProfile uint8

// VL53L0XProfile values.
const (
	VL53L0XProfileDefault      VL53L0XProfile = 0 // 0.25 MCPS, 14/10 PCLKs, 33 ms
	VL53L0XProfileLongRange    VL53L0XProfile = 1 // 0.10 MCPS, 18/14 PCLKs, 33 ms (dark conditions)
	VL53L0XProfileHighSpeed    VL53L0XProfile = 2 // 0.25 MCPS, 14/10 PCLKs, 20 ms
	VL53L0XProfileHighAccuracy VL53L0XProfile = 3 // 0.25 MCPS, 14/10 PCLKs, 200 ms
)

// VL53L0XMeasurement is the decoded result block returned by
// ReadMeasurement.
type VL53L0XMeasurement struct {
	DistanceMM         uint16  // range in mm
	RangeStatus        uint8   // device range status 0-15 (11 = valid)
	SignalRateMCPS     float32 // return signal rate in MCPS
	AmbientRateMCPS    float32 // ambient rate in MCPS
	EffectiveSpadCount float32 // effective SPAD return count
}

// Errors returned by the VL53L0X driver.
var (
	// ErrVL53L0XNotFound is returned by the constructors when
	// IDENTIFICATION_MODEL_ID is not 0xEE.
	ErrVL53L0XNotFound = errors.New("VL53L0X: not found (model ID mismatch)")
	// ErrVL53L0XTimeout is returned when a poll loop expires (500 ms).
	ErrVL53L0XTimeout = errors.New("VL53L0X: timeout")
	// ErrVL53L0XInvalidArgument is returned for an argument outside its
	// documented range; no bus transaction is made.
	ErrVL53L0XInvalidArgument = errors.New("VL53L0X: invalid argument")
)

func vl53l0xDecodeVcsel(reg uint8) uint32 { return (uint32(reg) + 1) << 1 }

func vl53l0xEncodeVcsel(pclks uint32) uint8 { return uint8((pclks >> 1) - 1) }

func vl53l0xMacroPeriodNs(pclks uint32) uint32 { return (2304*pclks*1655 + 500) / 1000 }

func vl53l0xMclksToUs(mclks, pclks uint32) uint32 {
	return (mclks*vl53l0xMacroPeriodNs(pclks) + 500) / 1000
}

func vl53l0xUsToMclks(us, pclks uint32) uint32 {
	period := vl53l0xMacroPeriodNs(pclks)
	return (us*1000 + period/2) / period
}

func vl53l0xDecodeTimeout(reg uint16) uint32 {
	return uint32(reg&0xFF)<<(reg>>8) + 1
}

func vl53l0xEncodeTimeout(mclks uint32) uint16 {
	if mclks == 0 {
		return 0
	}
	ls := mclks - 1
	var ms uint16
	for ls > 0xFF {
		ls >>= 1
		ms++
	}
	return ms<<8 | uint16(ls&0xFF)
}

type vl53l0xStepTimeouts struct {
	msrcUs, preMclks, preUs, finalPclks, finalUs uint32
}

// VL53L0XMinimal is the minimal VL53L0X interface: single-shot distance in
// mm.
//
// VL53L0X Time-of-Flight laser-ranging sensor (STMicroelectronics): a 940 nm
// VCSEL emitter, SPAD receiving array and embedded ranging microcontroller
// measuring absolute distance up to ~2 m. Registers, the tuning table and the
// init/calibration sequences follow ST's STSW-IMG005 API (the datasheet has
// no register map). Multi-byte registers are big-endian.
//
// Multiple sensors on one bus: all power up at 0x29. Hold every sensor's
// XSHUT low (each Connection disabled), then for each sensor in turn enable
// its XSHUT, construct a driver on 0x29, call SetAddress(new), and build the
// real driver on a Connection at the new address. The new address is
// volatile — it reverts to 0x29 on power-up or an XSHUT low pulse.
type VL53L0XMinimal struct {
	// Register access, polling, boot wait, the bus mutex, interrupt
	// delivery and re-addressing come from the shared VL53 family base.
	vl53Base

	stopVariable   uint8
	rangeStatus    uint8
	timingBudgetUs uint32
	result         [12]byte
}

// NewVL53L0XMinimal creates a VL53L0XMinimal and runs the full
// initialization sequence: XSHUT high if the connection has an EnPin, 1.2 ms
// boot wait, model ID check (ErrVL53L0XNotFound), 2V8 I/O mode, reference
// SPADs, default tuning, GPIO1 = new sample ready (active low), ~33 ms timing
// budget, VHV + phase reference calibration. Leaves the chip idle.
func NewVL53L0XMinimal(conn connection.Connection) (*VL53L0XMinimal, error) {
	d := &VL53L0XMinimal{vl53Base: newVL53Base(conn, 1, ErrVL53L0XTimeout)}
	if err := d.init(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *VL53L0XMinimal) wr(reg, value uint8) error {
	return d.write8(uint16(reg), value)
}

func (d *VL53L0XMinimal) rd(reg uint8) (uint8, error) {
	return d.read8(uint16(reg))
}

func (d *VL53L0XMinimal) wr16(reg uint8, value uint16) error {
	return d.write16(uint16(reg), value)
}

func (d *VL53L0XMinimal) rd16(reg uint8) (uint16, error) {
	return d.read16(uint16(reg))
}

func (d *VL53L0XMinimal) wr32(reg uint8, value uint32) error {
	return d.write32(uint16(reg), value)
}

// writes applies (reg, value) pairs in order, stopping at the first error.
func (d *VL53L0XMinimal) writes(pairs ...uint8) error {
	for i := 0; i+1 < len(pairs); i += 2 {
		if err := d.wr(pairs[i], pairs[i+1]); err != nil {
			return err
		}
	}
	return nil
}

func (d *VL53L0XMinimal) wait(reg, mask uint8, untilSet bool) error {
	return d.waitUntil(func() (bool, error) {
		v, err := d.rd(reg)
		return err == nil && (v&mask != 0) == untilSet, err
	})
}

func (d *VL53L0XMinimal) init() error {
	d.bootWait()

	model, err := d.rd(vl53l0xRegModelID)
	if err != nil {
		return fmt.Errorf("VL53L0X: device not responding: %w", err)
	}
	if model != VL53L0XModelID {
		return ErrVL53L0XNotFound
	}

	// 2V8 I/O mode, standard I²C mode.
	v, err := d.rd(vl53l0xRegVhvPadExtsupHv)
	if err != nil {
		return err
	}
	if err := d.writes(vl53l0xRegVhvPadExtsupHv, v|0x01, vl53l0xRegI2CMode, 0x00); err != nil {
		return err
	}

	// Stop variable.
	if err := d.writes(vl53l0xRegPowerForce, 0x01, vl53l0xRegPageSelect, 0x01, vl53l0xRegSysrangeStart, 0x00); err != nil {
		return err
	}
	if d.stopVariable, err = d.rd(vl53l0xRegStopVariable); err != nil {
		return err
	}
	if err := d.writes(vl53l0xRegSysrangeStart, 0x01, vl53l0xRegPageSelect, 0x00, vl53l0xRegPowerForce, 0x00); err != nil {
		return err
	}

	// Disable MSRC and pre-range signal-rate limit checks; 0.25 MCPS limit.
	if v, err = d.rd(vl53l0xRegMsrcConfigControl); err != nil {
		return err
	}
	if err := d.wr(vl53l0xRegMsrcConfigControl, v|0x12); err != nil {
		return err
	}
	if err := d.wr16(vl53l0xRegFinalMinCountRateRtn, 0x0020); err != nil {
		return err
	}
	if err := d.wr(vl53l0xRegSystemSequenceConfig, 0xFF); err != nil {
		return err
	}

	spadCount, spadIsAperture, err := d.spadInfo()
	if err != nil {
		return err
	}

	// Reference SPADs.
	refMap, err := d.readBlock(uint16(vl53l0xRegSpadEnablesRef0), 6)
	if err != nil {
		return err
	}
	if err := d.writes(vl53l0xRegPageSelect, 0x01, vl53l0xRegDynamicSpadStartOffset, 0x00,
		vl53l0xRegDynamicSpadNumReq, 0x2C, vl53l0xRegPageSelect, 0x00, vl53l0xRegRefEnStartSelect, 0xB4); err != nil {
		return err
	}
	first := 0
	if spadIsAperture {
		first = 12
	}
	var enabled uint8
	buf := make([]byte, 7)
	buf[0] = vl53l0xRegSpadEnablesRef0
	copy(buf[1:], refMap)
	for i := 0; i < 48; i++ {
		bit := byte(1) << (i % 8)
		if i < first || enabled == spadCount {
			buf[1+i/8] &^= bit
		} else if buf[1+i/8]&bit != 0 {
			enabled++
		}
	}
	if err := d.writeBlock(uint16(vl53l0xRegSpadEnablesRef0), buf[1:]...); err != nil {
		return err
	}

	// Default tuning settings.
	if err := d.writes(vl53l0xTuning[:]...); err != nil {
		return err
	}

	// GPIO1 = new sample ready, active low.
	if err := d.wr(vl53l0xRegSystemInterruptConfig, VL53L0XSourceNewSampleReady); err != nil {
		return err
	}
	if v, err = d.rd(vl53l0xRegGpioHvMuxActiveHigh); err != nil {
		return err
	}
	if err := d.writes(vl53l0xRegGpioHvMuxActiveHigh, v&^0x10, vl53l0xRegSystemInterruptClear, 0x01); err != nil {
		return err
	}

	budget, err := d.getTimingBudget()
	if err != nil {
		return err
	}
	if err := d.wr(vl53l0xRegSystemSequenceConfig, vl53l0xSeqOperating); err != nil {
		return err
	}
	if err := d.setTimingBudget(budget); err != nil {
		return err
	}
	return d.refCalibration()
}

func (d *VL53L0XMinimal) spadInfo() (uint8, bool, error) {
	if err := d.writes(vl53l0xRegPowerForce, 0x01, vl53l0xRegPageSelect, 0x01, vl53l0xRegSysrangeStart, 0x00,
		vl53l0xRegPageSelect, 0x06); err != nil {
		return 0, false, err
	}
	v, err := d.rd(0x83)
	if err != nil {
		return 0, false, err
	}
	if err := d.writes(0x83, v|0x04, vl53l0xRegPageSelect, 0x07, 0x81, 0x01, vl53l0xRegPowerForce, 0x01,
		0x94, 0x6B, 0x83, 0x00); err != nil {
		return 0, false, err
	}
	ready := d.wait(0x83, 0xFF, true)
	if err := d.wr(0x83, 0x01); err != nil {
		return 0, false, err
	}
	tmp, err := d.rd(0x92)
	if err != nil {
		return 0, false, err
	}
	if err := d.writes(0x81, 0x00, vl53l0xRegPageSelect, 0x06); err != nil {
		return 0, false, err
	}
	if v, err = d.rd(0x83); err != nil {
		return 0, false, err
	}
	if err := d.writes(0x83, v&^0x04, vl53l0xRegPageSelect, 0x01, vl53l0xRegSysrangeStart, 0x01,
		vl53l0xRegPageSelect, 0x00, vl53l0xRegPowerForce, 0x00); err != nil {
		return 0, false, err
	}
	if ready != nil {
		return 0, false, ready
	}
	return tmp & 0x7F, tmp>>7&0x01 == 1, nil
}

func (d *VL53L0XMinimal) singleRefCalibration(vhvInit uint8) error {
	if err := d.wr(vl53l0xRegSysrangeStart, 0x01|vhvInit); err != nil {
		return err
	}
	ready := d.wait(vl53l0xRegResultInterruptStatus, 0x07, true)
	if err := d.writes(vl53l0xRegSystemInterruptClear, 0x01, vl53l0xRegSysrangeStart, 0x00); err != nil {
		return err
	}
	return ready
}

func (d *VL53L0XMinimal) refCalibration() error {
	seq, err := d.rd(vl53l0xRegSystemSequenceConfig)
	if err != nil {
		return err
	}
	if err := d.wr(vl53l0xRegSystemSequenceConfig, 0x01); err != nil {
		return err
	}
	vhv := d.singleRefCalibration(0x40)
	if err := d.wr(vl53l0xRegSystemSequenceConfig, 0x02); err != nil {
		return err
	}
	phase := d.singleRefCalibration(0x00)
	if err := d.wr(vl53l0xRegSystemSequenceConfig, seq); err != nil {
		return err
	}
	if vhv != nil {
		return vhv
	}
	return phase
}

func (d *VL53L0XMinimal) stepTimeouts(enables uint8) (vl53l0xStepTimeouts, error) {
	var t vl53l0xStepTimeouts
	r, err := d.rd(vl53l0xRegPreRangeVcselPeriod)
	if err != nil {
		return t, err
	}
	prePclks := vl53l0xDecodeVcsel(r)
	if r, err = d.rd(vl53l0xRegMsrcConfigTimeout); err != nil {
		return t, err
	}
	t.msrcUs = vl53l0xMclksToUs(uint32(r)+1, prePclks)
	r16, err := d.rd16(vl53l0xRegPreRangeTimeout)
	if err != nil {
		return t, err
	}
	t.preMclks = vl53l0xDecodeTimeout(r16)
	t.preUs = vl53l0xMclksToUs(t.preMclks, prePclks)
	if r, err = d.rd(vl53l0xRegFinalRangeVcselPeriod); err != nil {
		return t, err
	}
	t.finalPclks = vl53l0xDecodeVcsel(r)
	if r16, err = d.rd16(vl53l0xRegFinalRangeTimeout); err != nil {
		return t, err
	}
	finalMclks := vl53l0xDecodeTimeout(r16)
	if enables&vl53l0xSeqPreRange != 0 && finalMclks >= t.preMclks {
		finalMclks -= t.preMclks
	}
	t.finalUs = vl53l0xMclksToUs(finalMclks, t.finalPclks)
	return t, nil
}

func vl53l0xFixedOverheadUs(enables uint8, t vl53l0xStepTimeouts) uint32 {
	budget := uint32(vl53l0xStartOverhead + vl53l0xEndOverhead)
	if enables&vl53l0xSeqTCC != 0 {
		budget += t.msrcUs + vl53l0xTccOverhead
	}
	if enables&vl53l0xSeqDSS != 0 {
		budget += 2 * (t.msrcUs + vl53l0xDssOverhead)
	} else if enables&vl53l0xSeqMSRC != 0 {
		budget += t.msrcUs + vl53l0xMsrcOverhead
	}
	if enables&vl53l0xSeqPreRange != 0 {
		budget += t.preUs + vl53l0xPreRangeOverhead
	}
	return budget
}

func (d *VL53L0XMinimal) getTimingBudget() (uint32, error) {
	enables, err := d.rd(vl53l0xRegSystemSequenceConfig)
	if err != nil {
		return 0, err
	}
	t, err := d.stepTimeouts(enables)
	if err != nil {
		return 0, err
	}
	budget := vl53l0xFixedOverheadUs(enables, t)
	if enables&vl53l0xSeqFinalRange != 0 {
		budget += t.finalUs + vl53l0xFinalRangeOverhead
	}
	return budget, nil
}

func (d *VL53L0XMinimal) setTimingBudget(budgetUs uint32) error {
	if budgetUs < vl53l0xMinTimingBudgetUs {
		return ErrVL53L0XInvalidArgument
	}
	enables, err := d.rd(vl53l0xRegSystemSequenceConfig)
	if err != nil {
		return err
	}
	t, err := d.stepTimeouts(enables)
	if err != nil {
		return err
	}
	used := vl53l0xFixedOverheadUs(enables, t)
	if enables&vl53l0xSeqFinalRange != 0 {
		used += vl53l0xFinalRangeOverhead
		if used > budgetUs {
			return ErrVL53L0XInvalidArgument
		}
		finalMclks := vl53l0xUsToMclks(budgetUs-used, t.finalPclks)
		if enables&vl53l0xSeqPreRange != 0 {
			finalMclks += t.preMclks
		}
		if err := d.wr16(vl53l0xRegFinalRangeTimeout, vl53l0xEncodeTimeout(finalMclks)); err != nil {
			return err
		}
	}
	d.timingBudgetUs = budgetUs
	return nil
}

func (d *VL53L0XMinimal) stopVariablePreamble() error {
	return d.writes(vl53l0xRegPowerForce, 0x01, vl53l0xRegPageSelect, 0x01, vl53l0xRegSysrangeStart, 0x00,
		vl53l0xRegStopVariable, d.stopVariable, vl53l0xRegSysrangeStart, 0x01, vl53l0xRegPageSelect, 0x00,
		vl53l0xRegPowerForce, 0x00)
}

func (d *VL53L0XMinimal) readResult() error {
	b, err := d.readBlock(uint16(vl53l0xRegResultRangeStatus), 12)
	if err != nil {
		return err
	}
	if err := d.wr(vl53l0xRegSystemInterruptClear, 0x01); err != nil {
		return err
	}
	copy(d.result[:], b)
	d.rangeStatus = (d.result[0] & 0x78) >> 3
	return nil
}

func (d *VL53L0XMinimal) resultWord(i int) uint16 {
	return uint16(d.result[i])<<8 | uint16(d.result[i+1])
}

func (d *VL53L0XMinimal) waitAndRead() (uint16, error) {
	if err := d.wait(vl53l0xRegResultInterruptStatus, 0x07, true); err != nil {
		return 0, err
	}
	if err := d.readResult(); err != nil {
		return 0, err
	}
	return d.resultWord(10), nil
}

// Distance takes one single-shot measurement and returns the distance in
// mm. It blocks for about one timing budget (33 ms by default) and returns
// the raw range even when the measurement is not valid — typically 8190 or
// 8191 with no target in range; check RangeValid. ErrVL53L0XTimeout if the
// measurement does not start or complete within 500 ms.
func (d *VL53L0XMinimal) Distance() (uint16, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	if err := d.stopVariablePreamble(); err != nil {
		return 0, err
	}
	if err := d.wr(vl53l0xRegSysrangeStart, 0x01); err != nil {
		return 0, err
	}
	if err := d.wait(vl53l0xRegSysrangeStart, 0x01, false); err != nil {
		return 0, err
	}
	return d.waitAndRead()
}

// RangeValid reports whether the device range status of the most recent
// measurement was 11 (range complete).
func (d *VL53L0XMinimal) RangeValid() bool {
	return d.rangeStatus == VL53L0XRangeStatusValid
}

// VL53L0XFull is the full VL53L0X interface — extends VL53L0XMinimal with
// continuous and timed ranging, the full measurement record, timing budget,
// signal-rate limit, VCSEL pulse periods, ranging profiles, offset and
// crosstalk compensation, reference recalibration, address change, distance
// thresholds, identification, and the Level-2 interrupt API.
type VL53L0XFull struct {
	*VL53L0XMinimal
}

// NewVL53L0XFull creates a VL53L0XFull; same initialization as
// NewVL53L0XMinimal.
func NewVL53L0XFull(conn connection.Connection) (*VL53L0XFull, error) {
	m, err := NewVL53L0XMinimal(conn)
	if err != nil {
		return nil, err
	}
	return &VL53L0XFull{VL53L0XMinimal: m}, nil
}

// StartContinuous starts continuous ranging: periodMs = 0 for back-to-back
// mode, otherwise timed mode with this inter-measurement period in ms
// (should be ≥ the timing budget).
func (d *VL53L0XFull) StartContinuous(periodMs uint32) error {
	d.bus.Lock()
	defer d.bus.Unlock()
	if err := d.stopVariablePreamble(); err != nil {
		return err
	}
	if periodMs == 0 {
		return d.wr(vl53l0xRegSysrangeStart, 0x02)
	}
	osc, err := d.rd16(vl53l0xRegOscCalibrateVal)
	if err != nil {
		return err
	}
	if osc != 0 {
		periodMs *= uint32(osc)
	}
	if err := d.wr32(vl53l0xRegSystemIntermeasurement, periodMs); err != nil {
		return err
	}
	return d.wr(vl53l0xRegSysrangeStart, 0x04)
}

// StopContinuous stops continuous ranging. It does not wait for a running
// measurement.
func (d *VL53L0XFull) StopContinuous() error {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.writes(vl53l0xRegSysrangeStart, 0x01, vl53l0xRegPageSelect, 0x01, vl53l0xRegSysrangeStart, 0x00,
		vl53l0xRegStopVariable, 0x00, vl53l0xRegSysrangeStart, 0x01, vl53l0xRegPageSelect, 0x00)
}

// ReadContinuous waits for the next continuous-mode result and returns its
// distance in mm (check RangeValid). ErrVL53L0XTimeout after 500 ms.
func (d *VL53L0XFull) ReadContinuous() (uint16, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.waitAndRead()
}

// DataReady reports whether a measurement is pending
// (RESULT_INTERRUPT_STATUS bits 2:0 non-zero) — non-blocking.
func (d *VL53L0XFull) DataReady() (bool, error) {
	v, err := d.rd(vl53l0xRegResultInterruptStatus)
	return v&0x07 != 0, err
}

// ReadMeasurement reads the full result block and clears the interrupt
// (non-blocking).
func (d *VL53L0XFull) ReadMeasurement() (VL53L0XMeasurement, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	if err := d.readResult(); err != nil {
		return VL53L0XMeasurement{}, err
	}
	return VL53L0XMeasurement{
		DistanceMM:         d.resultWord(10),
		RangeStatus:        d.rangeStatus,
		SignalRateMCPS:     float32(d.resultWord(6)) / 128,
		AmbientRateMCPS:    float32(d.resultWord(8)) / 128,
		EffectiveSpadCount: float32(d.resultWord(2)) / 256,
	}, nil
}

// RangeStatus returns the device range status (0-15) of the most recent
// measurement; 11 = valid, 4 = no target (MSRC).
func (d *VL53L0XFull) RangeStatus() uint8 {
	return d.rangeStatus
}

// SetTimingBudget sets the per-measurement timing budget in µs (≥ 20000).
// ErrVL53L0XInvalidArgument below 20000 µs or below the enabled steps'
// overhead.
func (d *VL53L0XFull) SetTimingBudget(budgetUs uint32) error {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.setTimingBudget(budgetUs)
}

// TimingBudget returns the timing budget in µs, computed from the current
// registers.
func (d *VL53L0XFull) TimingBudget() (uint32, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.getTimingBudget()
}

// SetSignalRateLimit sets the final-range return signal-rate limit in MCPS
// (0 to 511.99). Lower values extend range but admit noisier readings.
func (d *VL53L0XFull) SetSignalRateLimit(limitMcps float32) error {
	if limitMcps < 0 || limitMcps > 511.99 {
		return ErrVL53L0XInvalidArgument
	}
	return d.wr16(vl53l0xRegFinalMinCountRateRtn, uint16(limitMcps*128+0.5))
}

// SignalRateLimit returns the final-range return signal-rate limit in MCPS.
func (d *VL53L0XFull) SignalRateLimit() (float32, error) {
	v, err := d.rd16(vl53l0xRegFinalMinCountRateRtn)
	return float32(v) / 128, err
}

// SetVcselPulsePeriod sets a VCSEL pulse period in PCLKs (pre-range 12, 14,
// 16 or 18; final-range 8, 10, 12 or 14), then re-applies the timing budget
// and redoes the phase reference calibration.
func (d *VL53L0XFull) SetVcselPulsePeriod(periodType VL53L0XVcselPeriodType, pclks uint8) error {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.setVcselPulsePeriod(periodType, pclks)
}

func (d *VL53L0XFull) setVcselPulsePeriod(periodType VL53L0XVcselPeriodType, pclks uint8) error {
	preHigh, preOK := vl53l0xPrePhaseHigh[pclks]
	fin, finOK := vl53l0xFinalPhase[pclks]
	switch {
	case periodType == VL53L0XPreRange && !preOK,
		periodType == VL53L0XFinalRange && !finOK,
		periodType != VL53L0XPreRange && periodType != VL53L0XFinalRange:
		return ErrVL53L0XInvalidArgument
	}

	enables, err := d.rd(vl53l0xRegSystemSequenceConfig)
	if err != nil {
		return err
	}
	t, err := d.stepTimeouts(enables)
	if err != nil {
		return err
	}
	p := uint32(pclks)
	vcsel := vl53l0xEncodeVcsel(p)

	if periodType == VL53L0XPreRange {
		if err := d.writes(vl53l0xRegPreValidPhaseHigh, preHigh, vl53l0xRegPreValidPhaseLow, 0x08,
			vl53l0xRegPreRangeVcselPeriod, vcsel); err != nil {
			return err
		}
		if err := d.wr16(vl53l0xRegPreRangeTimeout, vl53l0xEncodeTimeout(vl53l0xUsToMclks(t.preUs, p))); err != nil {
			return err
		}
		m := vl53l0xUsToMclks(t.msrcUs, p)
		msrc := uint8(255)
		if m <= 256 && m > 0 {
			msrc = uint8(m - 1)
		}
		if err := d.wr(vl53l0xRegMsrcConfigTimeout, msrc); err != nil {
			return err
		}
	} else {
		if err := d.writes(vl53l0xRegFinalValidPhaseHigh, fin[0], vl53l0xRegFinalValidPhaseLow, fin[1],
			vl53l0xRegGlobalConfigVcselWidth, fin[2], vl53l0xRegPhasecalConfigTimeout, fin[3],
			vl53l0xRegPageSelect, 0x01, vl53l0xRegPhasecalConfigTimeout, fin[4], vl53l0xRegPageSelect, 0x00,
			vl53l0xRegFinalRangeVcselPeriod, vcsel); err != nil {
			return err
		}
		f := vl53l0xUsToMclks(t.finalUs, p)
		if enables&vl53l0xSeqPreRange != 0 {
			f += t.preMclks
		}
		if err := d.wr16(vl53l0xRegFinalRangeTimeout, vl53l0xEncodeTimeout(f)); err != nil {
			return err
		}
	}

	if err := d.setTimingBudget(d.timingBudgetUs); err != nil {
		return err
	}
	seq, err := d.rd(vl53l0xRegSystemSequenceConfig)
	if err != nil {
		return err
	}
	if err := d.wr(vl53l0xRegSystemSequenceConfig, 0x02); err != nil {
		return err
	}
	phase := d.singleRefCalibration(0x00)
	if err := d.wr(vl53l0xRegSystemSequenceConfig, seq); err != nil {
		return err
	}
	return phase
}

// VcselPulsePeriod returns a VCSEL pulse period in PCLKs.
func (d *VL53L0XFull) VcselPulsePeriod(periodType VL53L0XVcselPeriodType) (uint8, error) {
	reg := vl53l0xRegPreRangeVcselPeriod
	if periodType == VL53L0XFinalRange {
		reg = vl53l0xRegFinalRangeVcselPeriod
	}
	v, err := d.rd(reg)
	return uint8(vl53l0xDecodeVcsel(v)), err
}

// SetProfile applies a ranging profile: signal-rate limit, VCSEL periods
// (pre first), then timing budget.
func (d *VL53L0XFull) SetProfile(profile VL53L0XProfile) error {
	limit, pre, fin, budget := float32(0.25), uint8(14), uint8(10), uint32(33000)
	switch profile {
	case VL53L0XProfileDefault:
	case VL53L0XProfileLongRange:
		limit, pre, fin = 0.10, 18, 14
	case VL53L0XProfileHighSpeed:
		budget = 20000
	case VL53L0XProfileHighAccuracy:
		budget = 200000
	default:
		return ErrVL53L0XInvalidArgument
	}
	if err := d.SetSignalRateLimit(limit); err != nil {
		return err
	}
	d.bus.Lock()
	defer d.bus.Unlock()
	if err := d.setVcselPulsePeriod(VL53L0XPreRange, pre); err != nil {
		return err
	}
	if err := d.setVcselPulsePeriod(VL53L0XFinalRange, fin); err != nil {
		return err
	}
	return d.setTimingBudget(budget)
}

// SetOffset overrides the part-to-part range offset in mm (−512.0 to
// 511.75, 0.25 mm steps; volatile).
func (d *VL53L0XFull) SetOffset(offsetMm float32) error {
	if offsetMm < -512 || offsetMm > 511.75 {
		return ErrVL53L0XInvalidArgument
	}
	q := offsetMm * 4
	var steps int32
	if q >= 0 {
		steps = int32(q + 0.5)
	} else {
		steps = -int32(-q + 0.5)
	}
	return d.wr16(vl53l0xRegPartToPartRangeOffset, uint16(steps)&0x0FFF)
}

// Offset returns the part-to-part range offset in mm.
func (d *VL53L0XFull) Offset() (float32, error) {
	v, err := d.rd16(vl53l0xRegPartToPartRangeOffset)
	raw := int16(v & 0x0FFF)
	if raw&0x0800 != 0 {
		raw -= 0x1000
	}
	return float32(raw) * 0.25, err
}

// SetCrosstalkCompensation sets the crosstalk compensation peak rate in
// MCPS (volatile): 0 disables, otherwise 0 < rate < 8.0 from the host's own
// cover-glass calibration.
func (d *VL53L0XFull) SetCrosstalkCompensation(rateMcps float32) error {
	if rateMcps < 0 || rateMcps >= 8 {
		return ErrVL53L0XInvalidArgument
	}
	return d.wr16(vl53l0xRegCrosstalkCompensation, uint16(rateMcps*8192+0.5))
}

// Recalibrate re-runs the VHV and phase reference calibrations. Call it in
// software standby (not while continuous ranging), and after the die
// temperature changes by more than 8 °C.
func (d *VL53L0XFull) Recalibrate() error {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.refCalibration()
}

// SetAddress changes the chip's I²C address (0x08-0x77, volatile). The chip
// answers on the new address immediately; this driver instance becomes
// unusable — construct a new Connection at the new address and a new driver.
func (d *VL53L0XFull) SetAddress(address uint8) error {
	return d.setAddressReg(uint16(vl53l0xRegI2CSlaveDeviceAddress), address, ErrVL53L0XInvalidArgument)
}

// SetInterruptThresholds sets the distance thresholds in mm used by the
// threshold interrupt sources (2 mm resolution; lowMm ≤ highMm ≤ 8190).
func (d *VL53L0XFull) SetInterruptThresholds(lowMm, highMm uint16) error {
	if highMm < lowMm || highMm > 8190 {
		return ErrVL53L0XInvalidArgument
	}
	if err := d.wr16(vl53l0xRegSystemThreshLow, (lowMm/2)&0x0FFF); err != nil {
		return err
	}
	return d.wr16(vl53l0xRegSystemThreshHigh, (highMm/2)&0x0FFF)
}

// InterruptThresholds returns the distance thresholds (lowMm, highMm).
func (d *VL53L0XFull) InterruptThresholds() (uint16, uint16, error) {
	low, err := d.rd16(vl53l0xRegSystemThreshLow)
	if err != nil {
		return 0, 0, err
	}
	high, err := d.rd16(vl53l0xRegSystemThreshHigh)
	return (low & 0x0FFF) * 2, (high & 0x0FFF) * 2, err
}

// ModelID returns IDENTIFICATION_MODEL_ID (0xEE).
func (d *VL53L0XFull) ModelID() (uint8, error) {
	return d.rd(vl53l0xRegModelID)
}

// RevisionID returns IDENTIFICATION_REVISION_ID (0x10 on current silicon).
func (d *VL53L0XFull) RevisionID() (uint8, error) {
	return d.rd(vl53l0xRegRevisionID)
}

// EnableInterrupt selects the GPIO1 interrupt source (one of the
// VL53L0XSource* constants), replacing the active one. With a threshold
// source active, DataReady/ReadContinuous only see a pending status when the
// threshold condition is met.
func (d *VL53L0XFull) EnableInterrupt(source uint8) error {
	if source < VL53L0XSourceLevelLow || source > VL53L0XSourceNewSampleReady {
		return ErrVL53L0XInvalidArgument
	}
	return d.wr(vl53l0xRegSystemInterruptConfig, source)
}

// DisableInterrupt disables GPIO1 interrupts if source is the active one.
func (d *VL53L0XFull) DisableInterrupt(source uint8) error {
	v, err := d.rd(vl53l0xRegSystemInterruptConfig)
	if err != nil || v&0x07 != source {
		return err
	}
	return d.wr(vl53l0xRegSystemInterruptConfig, 0x00)
}

// PollInterrupt reads and clears the pending interrupt status: the
// VL53L0XSource* value that fired, or 0 if nothing is pending.
func (d *VL53L0XFull) PollInterrupt() (uint8, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	v, err := d.rd(vl53l0xRegResultInterruptStatus)
	if err != nil {
		return 0, err
	}
	status := v & 0x07
	if status != 0 {
		if err := d.wr(vl53l0xRegSystemInterruptClear, 0x01); err != nil {
			return 0, err
		}
	}
	return status, nil
}

// OnInterrupt subscribes callback to GPIO1 events; it receives the
// VL53L0XSource* value that fired (the status is read and cleared before the
// callback runs). With the connection's IntPin wired it fires on every
// falling edge (GPIO1 is active low); otherwise a 5 ms polling goroutine
// calls back whenever a status is pending. The polling fallback consumes
// results, so don't mix it with ReadContinuous.
func (d *VL53L0XFull) OnInterrupt(callback func(status uint8)) error {
	return d.subscribe(callback, d.PollInterrupt)
}

// OffInterrupt unsubscribes and stops delivery.
func (d *VL53L0XFull) OffInterrupt() error {
	return d.unsubscribeAll()
}

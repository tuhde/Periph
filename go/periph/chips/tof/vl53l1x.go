package tof

import (
	"errors"
	"fmt"

	"github.com/tuhde/Periph/go/periph/connection"
)

// VL53L1X register indices (ST ULD names), 16-bit.
const (
	vl53l1xRegI2CSlaveDeviceAddress uint16 = 0x0001
	vl53l1xRegVhvConfigLoopBound    uint16 = 0x0008
	vl53l1xRegVhvInit               uint16 = 0x000B
	vl53l1xRegXtalkPlaneOffset      uint16 = 0x0016
	vl53l1xRegXtalkXGradient        uint16 = 0x0018
	vl53l1xRegXtalkYGradient        uint16 = 0x001A
	vl53l1xRegPartToPartOffset      uint16 = 0x001E
	vl53l1xRegMmInnerOffset         uint16 = 0x0020
	vl53l1xRegMmOuterOffset         uint16 = 0x0022
	vl53l1xRegPadI2CHvExtsup        uint16 = 0x002E
	vl53l1xRegGpioExtsupHv          uint16 = 0x002F
	vl53l1xRegGpioHvMuxCtrl         uint16 = 0x0030
	vl53l1xRegGpioTioHvStatus       uint16 = 0x0031
	vl53l1xRegInterruptConfigGpio   uint16 = 0x0046
	vl53l1xRegPhasecalTimeout       uint16 = 0x004B
	vl53l1xRegRangeTimeoutA         uint16 = 0x005E
	vl53l1xRegRangeVcselPeriodA     uint16 = 0x0060
	vl53l1xRegRangeTimeoutB         uint16 = 0x0061
	vl53l1xRegRangeVcselPeriodB     uint16 = 0x0063
	vl53l1xRegSigmaThresh           uint16 = 0x0064
	vl53l1xRegMinCountRateRtnLimit  uint16 = 0x0066
	vl53l1xRegRangeValidPhaseHigh   uint16 = 0x0069
	vl53l1xRegIntermeasurement      uint16 = 0x006C
	vl53l1xRegThreshHigh            uint16 = 0x0072
	vl53l1xRegThreshLow             uint16 = 0x0074
	vl53l1xRegSdWoiSd0              uint16 = 0x0078
	vl53l1xRegSdInitialPhaseSd0     uint16 = 0x007A
	vl53l1xRegRoiCentreSpad         uint16 = 0x007F
	vl53l1xRegRoiXYSize             uint16 = 0x0080
	vl53l1xRegInterruptClear        uint16 = 0x0086
	vl53l1xRegModeStart             uint16 = 0x0087
	vl53l1xRegResultRangeStatus     uint16 = 0x0089
	vl53l1xRegOscCalibrateVal       uint16 = 0x00DE
	vl53l1xRegFirmwareSystemStatus  uint16 = 0x00E5
	vl53l1xRegModelID               uint16 = 0x010F
	vl53l1xRegModuleType            uint16 = 0x0110
	vl53l1xRegRevisionID            uint16 = 0x0111
	vl53l1xRegModeRoiCentreSpad     uint16 = 0x013E

	vl53l1xDefaultConfigStart uint16 = 0x002D
	vl53l1xCalibrationSamples        = 50
)

// vl53l1xDefaultConfiguration is ULD VL51L1X_DEFAULT_CONFIGURATION — opaque,
// written verbatim to 0x002D..0x0087, one byte per register.
var vl53l1xDefaultConfiguration = [91]uint8{
	0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x02, 0x08, 0x00, 0x08, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00,
	0x00, 0xFF, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0B, 0x00, 0x00, 0x02, 0x0A, 0x21,
	0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x38, 0xFF, 0x01, 0x00, 0x08, 0x00,
	0x00, 0x01, 0xCC, 0x0F, 0x01, 0xF1, 0x0D, 0x01, 0x68, 0x00, 0x80, 0x08, 0xB8, 0x00, 0x00, 0x00,
	0x00, 0x0F, 0x89, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0F, 0x0D, 0x0E, 0x0E, 0x00,
	0x00, 0x02, 0xC7, 0xFF, 0x9B, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
}

// vl53l1xStatusMap is ULD status_rtn: raw RESULT__RANGE_STATUS (bits 4:0) ->
// mapped range status.
var vl53l1xStatusMap = [24]uint8{255, 255, 255, 5, 2, 4, 1, 7, 3, 0, 255, 255, 9, 13, 255, 255,
	255, 255, 10, 6, 255, 255, 11, 12}

// vl53l1xBudgets: ms, short A, short B, long A, long B (0 = not available).
var vl53l1xBudgets = [7][5]uint16{
	{15, 0x001D, 0x0027, 0, 0},
	{20, 0x0051, 0x006E, 0x001E, 0x0022},
	{33, 0x00D6, 0x006E, 0x0060, 0x006E},
	{50, 0x01AE, 0x01E8, 0x00AD, 0x00C6},
	{100, 0x02E1, 0x0388, 0x01CC, 0x01EA},
	{200, 0x03E1, 0x0496, 0x02D9, 0x02F8},
	{500, 0x0591, 0x05C1, 0x048F, 0x04A4},
}

// VL53L1XI2CAddress is the power-on 7-bit I²C address.
const VL53L1XI2CAddress uint8 = vl53I2CAddress

// VL53L1XSensorID is the IDENTIFICATION__MODEL_ID + MODULE_TYPE word checked
// at construction.
const VL53L1XSensorID uint16 = 0xEACC

// VL53L1XModelID is IDENTIFICATION__MODEL_ID.
const VL53L1XModelID uint8 = 0xEA

// VL53L1XModuleType is IDENTIFICATION__MODULE_TYPE.
const VL53L1XModuleType uint8 = 0xCC

// VL53L1XRangeStatusValid is the mapped range status meaning "range valid".
const VL53L1XRangeStatusValid uint8 = 0

// Interrupt sources (logical family values, mutually exclusive).
const (
	VL53L1XSourceLevelLow       = vl53SourceLevelLow       // range < low threshold
	VL53L1XSourceLevelHigh      = vl53SourceLevelHigh      // range > high threshold
	VL53L1XSourceOutOfWindow    = vl53SourceOutOfWindow    // range < low or > high threshold
	VL53L1XSourceNewSampleReady = vl53SourceNewSampleReady // new measurement available (driver default)
	VL53L1XSourceInWindow       = vl53SourceInWindow       // low ≤ range ≤ high
)

// VL53L1XDistanceMode selects the distance mode for SetDistanceMode.
type VL53L1XDistanceMode uint8

// Distance modes.
const (
	VL53L1XDistanceModeShort VL53L1XDistanceMode = 1 // ~1.3 m, robust against ambient light
	VL53L1XDistanceModeLong  VL53L1XDistanceMode = 2 // up to 4 m in the dark (default)
)

// VL53L1XMeasurement is the decoded result block returned by ReadMeasurement.
type VL53L1XMeasurement struct {
	DistanceMM         uint16  // range in mm
	RangeStatus        uint8   // mapped range status (0 = valid, 255 = no update)
	SignalRateMCPS     float32 // return signal rate in MCPS
	AmbientRateMCPS    float32 // ambient rate in MCPS
	EffectiveSpadCount float32 // effective SPAD return count
}

var (
	// ErrVL53L1XNotFound is returned when the sensor ID word is not 0xEACC —
	// wrong chip, wrong address, or a wiring problem.
	ErrVL53L1XNotFound = errors.New("VL53L1X: not found (sensor ID mismatch)")
	// ErrVL53L1XTimeout is returned when a poll loop expires (500 ms).
	ErrVL53L1XTimeout = errors.New("VL53L1X: timeout")
	// ErrVL53L1XInvalidArgument is returned for an argument outside its
	// documented range (no bus transaction made).
	ErrVL53L1XInvalidArgument = errors.New("VL53L1X: invalid argument")
	// ErrVL53L1XUnknownDistanceMode is returned when
	// PHASECAL_CONFIG__TIMEOUT_MACROP holds neither distance mode's value.
	ErrVL53L1XUnknownDistanceMode = errors.New("VL53L1X: unknown distance mode")
)

func vl53l1xRound(x float32) int32 {
	if x >= 0 {
		return int32(x + 0.5)
	}
	return -int32(-x + 0.5)
}

// VL53L1XMinimal is the minimal VL53L1X interface: single-shot distance in
// mm.
//
// VL53L1X long-distance Time-of-Flight laser-ranging sensor
// (STMicroelectronics): a 940 nm VCSEL emitter, 16×16 SPAD receiving array
// behind a lens and an embedded ranging microcontroller measuring absolute
// distance up to 4 m at up to 50 Hz. Two distance modes (short ~1.3 m,
// robust in sunlight; long ~3.6–4 m in the dark), a 15–500 ms timing budget
// and a programmable region of interest (4×4 to 16×16 SPADs). Registers, the
// default configuration block and all sequences follow ST's Ultra Lite
// Driver (STSW-IMG009); registers use a 16-bit index, multi-byte registers
// are big-endian. Pin-to-pin compatible with the VL53L0X and shares its
// family base and public API shape.
//
// Multiple sensors on one bus: all VL53L0X/VL53L1X sensors power up at 0x29.
// Hold every sensor's XSHUT low (each Connection disabled), then for each
// sensor in turn enable its XSHUT, construct a driver on 0x29, call
// SetAddress(new), and build the real driver on a Connection at the new
// address. The new address is volatile.
type VL53L1XMinimal struct {
	// Register access, polling, boot wait, the bus mutex, interrupt
	// delivery and re-addressing come from the shared VL53 family base.
	vl53Base

	rangeStatus uint8
	result      [17]byte
}

// NewVL53L1XMinimal creates a VL53L1XMinimal and runs the full
// initialization sequence: XSHUT high if the connection has an EnPin, 1.2 ms
// boot wait, firmware boot poll, sensor ID check (ErrVL53L1XNotFound), ULD
// default configuration, 2V8 I/O mode, GPIO1 active low, settling ranging.
// Leaves the chip idle in long distance mode with a 100 ms timing budget.
func NewVL53L1XMinimal(conn connection.Connection) (*VL53L1XMinimal, error) {
	d := &VL53L1XMinimal{vl53Base: newVL53Base(conn, 2, ErrVL53L1XTimeout), rangeStatus: 255}
	if err := d.init(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *VL53L1XMinimal) writes(regValues ...uint16) error {
	for i := 0; i+1 < len(regValues); i += 2 {
		if err := d.write8(regValues[i], uint8(regValues[i+1])); err != nil {
			return err
		}
	}
	return nil
}

func (d *VL53L1XMinimal) isDataReady() (bool, error) {
	// GPIO1 is active low: line asserted (bit 0 == 0) means data ready.
	v, err := d.read8(vl53l1xRegGpioTioHvStatus)
	return err == nil && v&0x01 == 0, err
}

func (d *VL53L1XMinimal) init() error {
	d.bootWait()
	if err := d.waitUntil(func() (bool, error) {
		v, err := d.read8(vl53l1xRegFirmwareSystemStatus)
		return err == nil && v&0x01 != 0, err
	}); err != nil {
		return err
	}
	id, err := d.read16(vl53l1xRegModelID)
	if err != nil {
		return fmt.Errorf("VL53L1X: device not responding: %w", err)
	}
	if id != VL53L1XSensorID {
		return ErrVL53L1XNotFound
	}
	for i, v := range vl53l1xDefaultConfiguration {
		if err := d.write8(vl53l1xDefaultConfigStart+uint16(i), v); err != nil {
			return err
		}
	}
	// 2V8 I/O mode for I²C and GPIO1 pads; GPIO1 active low.
	if err := d.writes(vl53l1xRegPadI2CHvExtsup, 0x01, vl53l1xRegGpioExtsupHv, 0x01,
		vl53l1xRegGpioHvMuxCtrl, 0x11); err != nil {
		return err
	}
	// Settling ranging (ULD SensorInit), then two-bound VHV from the previous
	// temperature.
	if err := d.write8(vl53l1xRegModeStart, 0x40); err != nil {
		return err
	}
	ready := d.waitUntil(d.isDataReady)
	if err := d.writes(vl53l1xRegInterruptClear, 0x01, vl53l1xRegModeStart, 0x00,
		vl53l1xRegVhvConfigLoopBound, 0x09, vl53l1xRegVhvInit, 0x00); err != nil {
		return err
	}
	return ready
}

func (d *VL53L1XMinimal) readResult() error {
	b, err := d.readBlock(vl53l1xRegResultRangeStatus, 17)
	if err != nil {
		return err
	}
	copy(d.result[:], b)
	if err := d.write8(vl53l1xRegInterruptClear, 0x01); err != nil {
		return err
	}
	raw := d.result[0] & 0x1F
	d.rangeStatus = 255
	if int(raw) < len(vl53l1xStatusMap) {
		d.rangeStatus = vl53l1xStatusMap[raw]
	}
	return nil
}

func (d *VL53L1XMinimal) resultWord(i int) uint16 {
	return uint16(d.result[i])<<8 | uint16(d.result[i+1])
}

func (d *VL53L1XMinimal) waitAndRead() (uint16, error) {
	if err := d.waitUntil(d.isDataReady); err != nil {
		return 0, err
	}
	if err := d.readResult(); err != nil {
		return 0, err
	}
	return d.resultWord(13), nil
}

// Distance takes one single-shot measurement and returns the distance in mm.
// It blocks for about one timing budget (100 ms by default) and returns the
// raw range even when the measurement is not valid; check RangeValid.
// ErrVL53L1XTimeout if it does not complete within 500 ms.
func (d *VL53L1XMinimal) Distance() (uint16, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	if err := d.writes(vl53l1xRegInterruptClear, 0x01, vl53l1xRegModeStart, 0x10); err != nil {
		return 0, err
	}
	return d.waitAndRead()
}

// RangeValid reports whether the mapped range status of the most recent
// measurement was 0 (range valid).
func (d *VL53L1XMinimal) RangeValid() bool {
	return d.rangeStatus == VL53L1XRangeStatusValid
}

func (d *VL53L1XMinimal) activeSource() (uint8, error) {
	v, err := d.read8(vl53l1xRegInterruptConfigGpio)
	if err != nil {
		return 0, err
	}
	if v&0x20 != 0 {
		return VL53L1XSourceNewSampleReady, nil
	}
	return [4]uint8{VL53L1XSourceLevelLow, VL53L1XSourceLevelHigh, VL53L1XSourceOutOfWindow,
		VL53L1XSourceInWindow}[v&0x03], nil
}

// VL53L1XFull is the full VL53L1X interface — extends VL53L1XMinimal with
// timed continuous ranging, the full measurement record, distance mode,
// timing budget, inter-measurement period, signal and sigma thresholds,
// region of interest, offset and crosstalk compensation with calibration
// helpers, temperature update, address change, distance thresholds,
// identification, and the Level-2 interrupt API.
type VL53L1XFull struct {
	*VL53L1XMinimal
}

// NewVL53L1XFull creates a VL53L1XFull; same initialization as
// NewVL53L1XMinimal.
func NewVL53L1XFull(conn connection.Connection) (*VL53L1XFull, error) {
	m, err := NewVL53L1XMinimal(conn)
	if err != nil {
		return nil, err
	}
	return &VL53L1XFull{VL53L1XMinimal: m}, nil
}

// StartContinuous starts timed continuous ranging. periodMs is 0-60000; 0
// (and any value below the timing budget) runs at the timing budget, i.e.
// back-to-back — the chip requires period ≥ budget.
func (d *VL53L1XFull) StartContinuous(periodMs uint32) error {
	if periodMs > 60000 {
		return ErrVL53L1XInvalidArgument
	}
	d.bus.Lock()
	defer d.bus.Unlock()
	budget, err := d.timingBudget()
	if err != nil {
		return err
	}
	period := periodMs
	if budget/1000 > period {
		period = budget / 1000
	}
	if period == 0 {
		period = 1
	}
	if err := d.setInterMeasurement(period); err != nil {
		return err
	}
	return d.writes(vl53l1xRegInterruptClear, 0x01, vl53l1xRegModeStart, 0x40)
}

// StopContinuous stops continuous ranging. It does not wait for a running
// measurement.
func (d *VL53L1XFull) StopContinuous() error {
	return d.write8(vl53l1xRegModeStart, 0x00)
}

// ReadContinuous waits for the next continuous-mode result and returns its
// distance in mm (check RangeValid). ErrVL53L1XTimeout after 500 ms.
func (d *VL53L1XFull) ReadContinuous() (uint16, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.waitAndRead()
}

// DataReady reports whether GPIO__TIO_HV_STATUS shows the GPIO1 line
// asserted — non-blocking.
func (d *VL53L1XFull) DataReady() (bool, error) {
	return d.isDataReady()
}

// ReadMeasurement reads the full result block and clears the interrupt
// (non-blocking).
func (d *VL53L1XFull) ReadMeasurement() (VL53L1XMeasurement, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	if err := d.readResult(); err != nil {
		return VL53L1XMeasurement{}, err
	}
	return VL53L1XMeasurement{
		DistanceMM:         d.resultWord(13),
		RangeStatus:        d.rangeStatus,
		SignalRateMCPS:     float32(d.resultWord(15)) / 128,
		AmbientRateMCPS:    float32(d.resultWord(7)) / 128,
		EffectiveSpadCount: float32(d.resultWord(3)) / 256,
	}, nil
}

// RangeStatus returns the mapped range status of the most recent
// measurement: 0 = valid, 1 = sigma fail, 2 = signal fail, 4 = out of
// bounds, 7 = wrap-around, 255 = no update.
func (d *VL53L1XFull) RangeStatus() uint8 {
	return d.rangeStatus
}

func (d *VL53L1XFull) timingBudget() (uint32, error) {
	a, err := d.read16(vl53l1xRegRangeTimeoutA)
	if err != nil {
		return 0, err
	}
	for _, row := range vl53l1xBudgets {
		if row[1] == a || (row[3] != 0 && row[3] == a) {
			return uint32(row[0]) * 1000, nil
		}
	}
	return 0, nil
}

func (d *VL53L1XFull) distanceMode() (VL53L1XDistanceMode, error) {
	v, err := d.read8(vl53l1xRegPhasecalTimeout)
	if err != nil {
		return 0, err
	}
	switch v {
	case 0x14:
		return VL53L1XDistanceModeShort, nil
	case 0x0A:
		return VL53L1XDistanceModeLong, nil
	}
	return 0, ErrVL53L1XUnknownDistanceMode
}

func (d *VL53L1XFull) setTimingBudget(budgetUs uint32) error {
	mode, err := d.distanceMode()
	if err != nil {
		return err
	}
	if budgetUs%1000 != 0 {
		return ErrVL53L1XInvalidArgument
	}
	col := 3
	if mode == VL53L1XDistanceModeShort {
		col = 1
	}
	for _, row := range vl53l1xBudgets {
		if uint32(row[0])*1000 == budgetUs && row[col] != 0 {
			if err := d.write16(vl53l1xRegRangeTimeoutA, row[col]); err != nil {
				return err
			}
			return d.write16(vl53l1xRegRangeTimeoutB, row[col+1])
		}
	}
	return ErrVL53L1XInvalidArgument
}

func (d *VL53L1XFull) setInterMeasurement(periodMs uint32) error {
	clock, err := d.read16(vl53l1xRegOscCalibrateVal)
	if err != nil {
		return err
	}
	return d.write32(vl53l1xRegIntermeasurement, uint32(uint64(clock&0x03FF)*uint64(periodMs)*1075/1000))
}

// SetTimingBudget sets the per-measurement timing budget in µs (ULD table
// values only): 15000 (short mode only), 20000, 33000, 50000, 100000,
// 200000 or 500000. ErrVL53L1XInvalidArgument otherwise.
func (d *VL53L1XFull) SetTimingBudget(budgetUs uint32) error {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.setTimingBudget(budgetUs)
}

// TimingBudget returns the timing budget in µs decoded from
// RANGE_CONFIG__TIMEOUT_MACROP_A, or 0 if the register holds no table value.
func (d *VL53L1XFull) TimingBudget() (uint32, error) {
	return d.timingBudget()
}

// SetDistanceMode selects short or long distance mode, keeping the timing
// budget (100 ms if the current budget is unknown). Switching to long at a
// 15 ms budget is ErrVL53L1XInvalidArgument.
func (d *VL53L1XFull) SetDistanceMode(mode VL53L1XDistanceMode) error {
	if mode != VL53L1XDistanceModeShort && mode != VL53L1XDistanceModeLong {
		return ErrVL53L1XInvalidArgument
	}
	d.bus.Lock()
	defer d.bus.Unlock()
	budget, err := d.timingBudget()
	if err != nil {
		return err
	}
	if budget == 0 {
		budget = 100000
	}
	if mode == VL53L1XDistanceModeLong && budget == 15000 {
		return ErrVL53L1XInvalidArgument
	}
	regs := [6]uint16{0x0A, 0x0F, 0x0D, 0xB8, 0x0F0D, 0x0E0E}
	if mode == VL53L1XDistanceModeShort {
		regs = [6]uint16{0x14, 0x07, 0x05, 0x38, 0x0705, 0x0606}
	}
	if err := d.writes(vl53l1xRegPhasecalTimeout, regs[0], vl53l1xRegRangeVcselPeriodA, regs[1],
		vl53l1xRegRangeVcselPeriodB, regs[2], vl53l1xRegRangeValidPhaseHigh, regs[3]); err != nil {
		return err
	}
	if err := d.write16(vl53l1xRegSdWoiSd0, regs[4]); err != nil {
		return err
	}
	if err := d.write16(vl53l1xRegSdInitialPhaseSd0, regs[5]); err != nil {
		return err
	}
	return d.setTimingBudget(budget)
}

// DistanceMode returns the current distance mode
// (ErrVL53L1XUnknownDistanceMode for an unrecognised register value).
func (d *VL53L1XFull) DistanceMode() (VL53L1XDistanceMode, error) {
	return d.distanceMode()
}

// SetInterMeasurement sets the continuous-mode inter-measurement period in
// ms (1-60000); it should be ≥ the timing budget (StartContinuous enforces
// this).
func (d *VL53L1XFull) SetInterMeasurement(periodMs uint32) error {
	if periodMs < 1 || periodMs > 60000 {
		return ErrVL53L1XInvalidArgument
	}
	return d.setInterMeasurement(periodMs)
}

// InterMeasurement returns the continuous-mode inter-measurement period in
// ms (0 if the oscillator calibration reads 0).
func (d *VL53L1XFull) InterMeasurement() (uint32, error) {
	clock, err := d.read16(vl53l1xRegOscCalibrateVal)
	if err != nil {
		return 0, err
	}
	pll := uint64(clock & 0x03FF)
	if pll == 0 {
		return 0, nil
	}
	raw, err := d.read32(vl53l1xRegIntermeasurement)
	if err != nil {
		return 0, err
	}
	return uint32(uint64(raw) * 1000 / (pll * 1075)), nil
}

// SetSignalRateLimit sets the minimum return signal rate for a valid result
// in MCPS, 0-511.99 (default 1.0).
func (d *VL53L1XFull) SetSignalRateLimit(limitMcps float32) error {
	if limitMcps < 0 || limitMcps > 511.99 {
		return ErrVL53L1XInvalidArgument
	}
	return d.write16(vl53l1xRegMinCountRateRtnLimit, uint16(limitMcps*128+0.5))
}

// SignalRateLimit returns the minimum return signal rate in MCPS.
func (d *VL53L1XFull) SignalRateLimit() (float32, error) {
	v, err := d.read16(vl53l1xRegMinCountRateRtnLimit)
	return float32(v) / 128, err
}

// SetSigmaThreshold sets the maximum estimated standard deviation for a
// valid result in mm, 0-16383 (default 90).
func (d *VL53L1XFull) SetSigmaThreshold(sigmaMm uint16) error {
	if sigmaMm > 16383 {
		return ErrVL53L1XInvalidArgument
	}
	return d.write16(vl53l1xRegSigmaThresh, sigmaMm<<2)
}

// SigmaThreshold returns the sigma threshold in mm.
func (d *VL53L1XFull) SigmaThreshold() (uint16, error) {
	v, err := d.read16(vl53l1xRegSigmaThresh)
	return v >> 2, err
}

// SetROI sets the receiving region-of-interest size in SPADs (4-16 each);
// sizes above 10 re-centre the ROI on SPAD 199 (array centre).
func (d *VL53L1XFull) SetROI(width, height uint8) error {
	if width < 4 || width > 16 || height < 4 || height > 16 {
		return ErrVL53L1XInvalidArgument
	}
	d.bus.Lock()
	defer d.bus.Unlock()
	if width > 10 || height > 10 {
		if err := d.write8(vl53l1xRegRoiCentreSpad, 199); err != nil {
			return err
		}
	}
	return d.write8(vl53l1xRegRoiXYSize, (height-1)<<4|(width-1))
}

// ROI returns the region-of-interest size (width, height) in SPADs.
func (d *VL53L1XFull) ROI() (uint8, uint8, error) {
	v, err := d.read8(vl53l1xRegRoiXYSize)
	return v&0x0F + 1, v>>4 + 1, err
}

// SetROICenter moves the region of interest to a centre SPAD (ST UM2555
// numbering, 199 = array centre). The caller keeps the ROI inside the array.
func (d *VL53L1XFull) SetROICenter(spad uint8) error {
	return d.write8(vl53l1xRegRoiCentreSpad, spad)
}

// ROICenter returns the region-of-interest centre SPAD.
func (d *VL53L1XFull) ROICenter() (uint8, error) {
	return d.read8(vl53l1xRegRoiCentreSpad)
}

// OpticalCenter returns the factory-measured optical-centre SPAD from NVM;
// pass it to SetROICenter to align the ROI with this part's lens.
func (d *VL53L1XFull) OpticalCenter() (uint8, error) {
	return d.read8(vl53l1xRegModeRoiCentreSpad)
}

func (d *VL53L1XFull) setOffset(offsetMm float32) error {
	if offsetMm < -1024 || offsetMm > 1023.75 {
		return ErrVL53L1XInvalidArgument
	}
	if err := d.write16(vl53l1xRegPartToPartOffset, uint16(vl53l1xRound(offsetMm*4))&0x1FFF); err != nil {
		return err
	}
	if err := d.write16(vl53l1xRegMmInnerOffset, 0); err != nil {
		return err
	}
	return d.write16(vl53l1xRegMmOuterOffset, 0)
}

// SetOffset overrides the part-to-part range offset in mm (volatile),
// -1024.0 to 1023.75 in 0.25 mm steps.
func (d *VL53L1XFull) SetOffset(offsetMm float32) error {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.setOffset(offsetMm)
}

// Offset returns the part-to-part range offset in mm.
func (d *VL53L1XFull) Offset() (float32, error) {
	v, err := d.read16(vl53l1xRegPartToPartOffset)
	if err != nil {
		return 0, err
	}
	raw := int16(v & 0x1FFF)
	if raw&0x1000 != 0 {
		raw -= 0x2000
	}
	return float32(raw) * 0.25, nil
}

func (d *VL53L1XFull) setCrosstalk(rateMcps float32) error {
	if rateMcps < 0 || rateMcps >= 0.128 {
		return ErrVL53L1XInvalidArgument
	}
	raw := uint32(rateMcps*512000 + 0.5)
	if raw > 0xFFFF {
		raw = 0xFFFF
	}
	if err := d.write16(vl53l1xRegXtalkXGradient, 0); err != nil {
		return err
	}
	if err := d.write16(vl53l1xRegXtalkYGradient, 0); err != nil {
		return err
	}
	return d.write16(vl53l1xRegXtalkPlaneOffset, uint16(raw))
}

// SetCrosstalkCompensation sets the per-SPAD crosstalk compensation rate in
// MCPS (volatile); 0 disables, otherwise 0 < rate < 0.128.
func (d *VL53L1XFull) SetCrosstalkCompensation(rateMcps float32) error {
	d.bus.Lock()
	defer d.bus.Unlock()
	return d.setCrosstalk(rateMcps)
}

// CrosstalkCompensation returns the per-SPAD crosstalk compensation rate in
// MCPS.
func (d *VL53L1XFull) CrosstalkCompensation() (float32, error) {
	v, err := d.read16(vl53l1xRegXtalkPlaneOffset)
	return float32(v) / 512000, err
}

// collect ranges 50 timed-mode samples, calling acc with each result block;
// it always stops ranging.
func (d *VL53L1XFull) collect(acc func(r *[17]byte)) error {
	if err := d.writes(vl53l1xRegInterruptClear, 0x01, vl53l1xRegModeStart, 0x40); err != nil {
		return err
	}
	var outcome error
	for i := 0; i < vl53l1xCalibrationSamples; i++ {
		if outcome = d.waitUntil(d.isDataReady); outcome != nil {
			break
		}
		if outcome = d.readResult(); outcome != nil {
			break
		}
		acc(&d.result)
	}
	if err := d.write8(vl53l1xRegModeStart, 0x00); err != nil && outcome == nil {
		outcome = err
	}
	return outcome
}

func vl53l1xWord(r *[17]byte, i int) uint16 {
	return uint16(r[i])<<8 | uint16(r[i+1])
}

// CalibrateOffset measures and applies the range offset against a target at
// a known distance (ULD CalibrateOffset; ST recommends 88 % white at 140
// mm). It ranges 50 times with the offset zeroed and must not be called
// while ranging. Returns the applied offset in mm (target − mean distance);
// store it and re-apply it with SetOffset after each power-up.
func (d *VL53L1XFull) CalibrateOffset(targetMm uint16) (float32, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	for _, reg := range []uint16{vl53l1xRegPartToPartOffset, vl53l1xRegMmInnerOffset, vl53l1xRegMmOuterOffset} {
		if err := d.write16(reg, 0); err != nil {
			return 0, err
		}
	}
	var sum uint32
	if err := d.collect(func(r *[17]byte) { sum += uint32(vl53l1xWord(r, 13)) }); err != nil {
		return 0, err
	}
	offset := float32(targetMm) - float32(sum)/vl53l1xCalibrationSamples
	return offset, d.setOffset(offset)
}

// CalibrateCrosstalk measures and applies crosstalk compensation for a cover
// glass (ULD CalibrateXtalk; ST uses a 17 % grey target where the sensor
// starts to under-range). It ranges 50 times with compensation off and must
// not be called while ranging. Returns the applied per-SPAD rate in MCPS
// (0-0.127); store it and re-apply it with SetCrosstalkCompensation.
func (d *VL53L1XFull) CalibrateCrosstalk(targetMm uint16) (float32, error) {
	if targetMm == 0 {
		return 0, ErrVL53L1XInvalidArgument
	}
	d.bus.Lock()
	defer d.bus.Unlock()
	if err := d.write16(vl53l1xRegXtalkPlaneOffset, 0); err != nil {
		return 0, err
	}
	var distance uint32
	var signal, spads float32
	if err := d.collect(func(r *[17]byte) {
		distance += uint32(vl53l1xWord(r, 13))
		signal += float32(vl53l1xWord(r, 15)) / 128
		spads += float32(vl53l1xWord(r, 3)) / 256
	}); err != nil {
		return 0, err
	}
	n := float32(vl53l1xCalibrationSamples)
	meanDistance, meanSignal, meanSpads := float32(distance)/n, signal/n, spads/n
	var rate float32
	if meanSpads > 0 {
		rate = meanSignal * (1 - meanDistance/float32(targetMm)) / meanSpads
	}
	if rate < 0 {
		rate = 0
	}
	if rate > 0.127 {
		rate = 0.127
	}
	return rate, d.setCrosstalk(rate)
}

// Recalibrate runs the temperature update (ULD StartTemperatureUpdate). Call
// in software standby (not while ranging), after the temperature changes by
// more than about 8 °C.
func (d *VL53L1XFull) Recalibrate() error {
	d.bus.Lock()
	defer d.bus.Unlock()
	if err := d.writes(vl53l1xRegVhvConfigLoopBound, 0x81, vl53l1xRegVhvInit, 0x92, vl53l1xRegModeStart, 0x40); err != nil {
		return err
	}
	ready := d.waitUntil(d.isDataReady)
	if err := d.writes(vl53l1xRegInterruptClear, 0x01, vl53l1xRegModeStart, 0x00,
		vl53l1xRegVhvConfigLoopBound, 0x09, vl53l1xRegVhvInit, 0x00); err != nil {
		return err
	}
	return ready
}

// SetAddress changes the chip's I²C address (0x08-0x77, volatile). The chip
// answers on the new address immediately; this driver instance becomes
// unusable — construct a new Connection at the new address and a new driver.
func (d *VL53L1XFull) SetAddress(address uint8) error {
	return d.setAddressReg(vl53l1xRegI2CSlaveDeviceAddress, address, ErrVL53L1XInvalidArgument)
}

// SetInterruptThresholds sets the distance thresholds in mm used by the
// threshold interrupt sources (lowMm ≤ highMm).
func (d *VL53L1XFull) SetInterruptThresholds(lowMm, highMm uint16) error {
	if highMm < lowMm {
		return ErrVL53L1XInvalidArgument
	}
	if err := d.write16(vl53l1xRegThreshHigh, highMm); err != nil {
		return err
	}
	return d.write16(vl53l1xRegThreshLow, lowMm)
}

// InterruptThresholds returns the distance thresholds (lowMm, highMm).
func (d *VL53L1XFull) InterruptThresholds() (uint16, uint16, error) {
	low, err := d.read16(vl53l1xRegThreshLow)
	if err != nil {
		return 0, 0, err
	}
	high, err := d.read16(vl53l1xRegThreshHigh)
	return low, high, err
}

// ModelID returns IDENTIFICATION__MODEL_ID (0xEA).
func (d *VL53L1XFull) ModelID() (uint8, error) {
	return d.read8(vl53l1xRegModelID)
}

// ModuleType returns IDENTIFICATION__MODULE_TYPE (0xCC).
func (d *VL53L1XFull) ModuleType() (uint8, error) {
	return d.read8(vl53l1xRegModuleType)
}

// RevisionID returns IDENTIFICATION__REVISION_ID, the mask revision (0x10).
func (d *VL53L1XFull) RevisionID() (uint8, error) {
	return d.read8(vl53l1xRegRevisionID)
}

// EnableInterrupt selects the GPIO1 interrupt source (one of the
// VL53L1XSource* constants, 1-5), replacing the active one. With a threshold
// source active, DataReady/ReadContinuous only see a pending result when the
// threshold condition is met.
func (d *VL53L1XFull) EnableInterrupt(source uint8) error {
	config := map[uint8]uint8{
		VL53L1XSourceLevelLow: 0x00, VL53L1XSourceLevelHigh: 0x01, VL53L1XSourceOutOfWindow: 0x02,
		VL53L1XSourceNewSampleReady: 0x20, VL53L1XSourceInWindow: 0x03,
	}
	v, ok := config[source]
	if !ok {
		return ErrVL53L1XInvalidArgument
	}
	return d.write8(vl53l1xRegInterruptConfigGpio, v)
}

// DisableInterrupt reverts to VL53L1XSourceNewSampleReady if source is the
// active threshold source. The chip has no disabled state, so disabling
// VL53L1XSourceNewSampleReady is a no-op; use OffInterrupt to stop callbacks.
func (d *VL53L1XFull) DisableInterrupt(source uint8) error {
	if source == VL53L1XSourceNewSampleReady {
		return nil
	}
	d.bus.Lock()
	defer d.bus.Unlock()
	active, err := d.activeSource()
	if err != nil || active != source {
		return err
	}
	return d.write8(vl53l1xRegInterruptConfigGpio, 0x20)
}

// PollInterrupt reads and clears a pending interrupt: the active
// VL53L1XSource* value if GPIO1 is asserted, else 0.
func (d *VL53L1XFull) PollInterrupt() (uint8, error) {
	d.bus.Lock()
	defer d.bus.Unlock()
	ready, err := d.isDataReady()
	if err != nil || !ready {
		return 0, err
	}
	if err := d.write8(vl53l1xRegInterruptClear, 0x01); err != nil {
		return 0, err
	}
	return d.activeSource()
}

// OnInterrupt subscribes callback to GPIO1 events; it receives the active
// VL53L1XSource* value (the interrupt is cleared before the callback runs).
// With the connection's IntPin wired it fires on every falling edge (GPIO1
// is active low); otherwise a 5 ms polling goroutine calls back whenever a
// result is pending. The polling fallback consumes results, so don't mix it
// with ReadContinuous.
func (d *VL53L1XFull) OnInterrupt(callback func(status uint8)) error {
	return d.subscribe(callback, d.PollInterrupt)
}

// OffInterrupt unsubscribes and stops delivery.
func (d *VL53L1XFull) OffInterrupt() error {
	return d.unsubscribeAll()
}

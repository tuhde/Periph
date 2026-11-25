package motor

import (
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// DRV2605 register addresses.
const (
	drv2605RegStatus       uint8 = 0x00
	drv2605RegMode         uint8 = 0x01
	drv2605RegRTPInput     uint8 = 0x02
	drv2605RegLibrary      uint8 = 0x03
	drv2605RegSeq1         uint8 = 0x04
	drv2605RegGo           uint8 = 0x0C
	drv2605RegODT          uint8 = 0x0D
	drv2605RegATHCtrl      uint8 = 0x11
	drv2605RegRatedVoltage uint8 = 0x16
	drv2605RegODClamp      uint8 = 0x17
	drv2605RegACalComp     uint8 = 0x18
	drv2605RegFeedback     uint8 = 0x1A
	drv2605RegControl1     uint8 = 0x1B
	drv2605RegControl2     uint8 = 0x1C
	drv2605RegControl3     uint8 = 0x1D
	drv2605RegControl4     uint8 = 0x1E
	drv2605RegVBat         uint8 = 0x21
	drv2605RegLRAPeriod    uint8 = 0x22
)

const (
	drv2605ModeDevReset uint8 = 0x80
	drv2605ModeStandby  uint8 = 0x40
)

// DRV2605I2CAddress is the fixed 7-bit I²C address.
const DRV2605I2CAddress uint8 = 0x5A

// DRV2605DeviceID is the expected DEVICE_ID of the DRV2605.
const DRV2605DeviceID uint8 = 3

// Named ROM effect identifiers (TS2200 library).
const (
	DRV2605EffectStrongClick   uint8 = 1
	DRV2605EffectSharpClick    uint8 = 4
	DRV2605EffectSoftBump      uint8 = 7
	DRV2605EffectDoubleClick   uint8 = 10
	DRV2605EffectTripleClick   uint8 = 12
	DRV2605EffectStrongBuzz    uint8 = 14
	DRV2605EffectAlert750ms    uint8 = 15
	DRV2605EffectAlert1000ms   uint8 = 16
	DRV2605EffectBuzz          uint8 = 47
	DRV2605EffectPulsingStrong uint8 = 52
	DRV2605EffectLongBuzz      uint8 = 118
)

// MODE register values.
const (
	DRV2605ModeInternalTrigger uint8 = 0
	DRV2605ModeExternalEdge    uint8 = 1
	DRV2605ModeExternalLevel   uint8 = 2
	DRV2605ModePWMAnalog       uint8 = 3
	DRV2605ModeAudioToVibe     uint8 = 4
	DRV2605ModeRTP             uint8 = 5
	DRV2605ModeDiagnostics     uint8 = 6
	DRV2605ModeAutoCalibration uint8 = 7
)

var (
	drv2605SampleTimeS = [4]float32{150e-6, 200e-6, 250e-6, 300e-6}
	drv2605PeakTimesMs = [4]uint16{10, 20, 30, 40}
	drv2605FiltersHz   = [4]uint16{100, 125, 150, 200}
)

// Errors returned by the DRV2605 drivers.
var (
	// ErrDRV2605InvalidArgument is returned for an out-of-range or inconsistent argument (no bus access is made).
	ErrDRV2605InvalidArgument = errors.New("DRV2605: invalid argument")
	// ErrDRV2605InvalidState is returned when the operation is not allowed in the current configuration
	// (unidirectional input while open-loop, ERM closed-loop clamp in volts, OTP already programmed).
	ErrDRV2605InvalidState = errors.New("DRV2605: operation not allowed in the current configuration")
	// ErrDRV2605Timeout is returned when a reset or OTP operation does not finish in time.
	ErrDRV2605Timeout = errors.New("DRV2605: timeout")
)

// DRV2605SequenceStep is one step of a waveform sequence for PlaySequence.
type DRV2605SequenceStep struct {
	Wait  bool   // false = ROM effect, true = delay
	Value uint16 // effect identifier 1-123, or delay in ms (0-1270, 10 ms steps)
}

// DRV2605Effect returns a ROM-effect sequence step.
func DRV2605Effect(id uint8) DRV2605SequenceStep { return DRV2605SequenceStep{Value: uint16(id)} }

// DRV2605Wait returns a delay sequence step of ms milliseconds.
func DRV2605Wait(ms uint16) DRV2605SequenceStep { return DRV2605SequenceStep{Wait: true, Value: ms} }

// DRV2605Status is the decoded STATUS register (reading it clears the latched flags).
type DRV2605Status struct {
	DiagFailed  bool // last calibration or diagnostic run failed
	FBTimeout   bool // feedback controller timed out
	OverTemp    bool // overtemperature shutdown latched
	OverCurrent bool // overcurrent latched
}

// DRV2605Calibration is an auto-calibration result or the stored calibration.
type DRV2605Calibration struct {
	OK       bool  // calibration converged (always true from GetCalibration)
	Comp     uint8 // A_CAL_COMP
	BEMF     uint8 // A_CAL_BEMF
	BEMFGain uint8 // BEMF_GAIN, 0-3
}

// DRV2605AdvancedConfig holds the CONTROL1-CONTROL3 fields set by SetAdvanced.
type DRV2605AdvancedConfig struct {
	StartupBoost    bool  // higher loop gain during overdrive
	BrakeStabilizer bool  // reduce loop gain near the end of braking
	SampleTime      uint8 // LRA auto-resonance sample time, 0-3
	BlankingTime    uint8 // blanking time, 0-3
	IdissTime       uint8 // current-dissipation time, 0-3
	LRADriveTwice   bool  // update the LRA drive twice per cycle
	SupplyComp      bool  // keep supply compensation enabled
	NoiseGate       uint8 // noise-gate threshold, 0-3
}

// DefaultDRV2605AdvancedConfig returns the SetAdvanced defaults (the chip's reset values).
func DefaultDRV2605AdvancedConfig() DRV2605AdvancedConfig {
	return DRV2605AdvancedConfig{StartupBoost: true, BrakeStabilizer: true, SampleTime: 3, BlankingTime: 1,
		IdissTime: 1, SupplyComp: true, NoiseGate: 2}
}

// DRV2605AudioToVibeConfig holds the SetAudioToVibe parameters.
type DRV2605AudioToVibeConfig struct {
	ACCouple   bool    // enable the 0.9 V common-mode for AC-coupled input
	PeakTimeMs uint16  // peak-detection time: 10, 20, 30 or 40 ms
	FilterHz   uint16  // low-pass filter corner: 100, 125, 150 or 200 Hz
	MinInputV  float32 // minimum input level, V peak-to-peak (0-1.8)
	MaxInputV  float32 // maximum input level, V peak-to-peak (0-1.8)
	MinDrive   float32 // minimum output drive fraction (0.0-1.0)
	MaxDrive   float32 // maximum output drive fraction (0.0-1.0)
}

// DefaultDRV2605AudioToVibeConfig returns the SetAudioToVibe defaults.
func DefaultDRV2605AudioToVibeConfig() DRV2605AudioToVibeConfig {
	return DRV2605AudioToVibeConfig{ACCouple: true, PeakTimeMs: 20, FilterHz: 125, MinInputV: 0.1, MaxInputV: 1.8,
		MinDrive: 0.1, MaxDrive: 1.0}
}

func drv2605ClampCode(v float32) uint8 {
	c := int(v + 0.5)
	if c < 0 {
		return 0
	}
	if c > 255 {
		return 255
	}
	return uint8(c)
}

func drv2605DriveTimeCode(ms float32, lra bool) uint8 {
	var c int
	if lra {
		c = int((ms-0.5)/0.1 + 0.5)
	} else {
		c = int((ms-1.0)/0.2 + 0.5)
	}
	if c < 0 {
		c = 0
	}
	if c > 31 {
		c = 31
	}
	return uint8(c)
}

func drv2605Sqrt(x float32) float32 { return float32(math.Sqrt(float64(x))) }

// DRV2605Minimal plays the built-in 123-effect TS2200 ROM library of a DRV2605
// haptic driver (Texas Instruments) for ERM and LRA actuators. The I²C address
// is fixed at 0x5A and the EN pin must be high for register access.
type DRV2605Minimal struct {
	conn    connection.RegisterConnection
	mode    uint8
	standby bool
}

// NewDRV2605Minimal runs the initialization sequence: DEV_RESET, DEVICE_ID
// check, leave standby, actuator selection (ERM: library A, open loop; LRA: LRA
// library, closed loop with auto-resonance).
func NewDRV2605Minimal(conn connection.RegisterConnection, lra bool) (*DRV2605Minimal, error) {
	d := &DRV2605Minimal{conn: conn}
	if err := d.resetChip(); err != nil {
		return nil, err
	}
	st, err := d.readReg(drv2605RegStatus)
	if err != nil {
		return nil, err
	}
	if st>>5 != DRV2605DeviceID {
		return nil, fmt.Errorf("DRV2605: unexpected DEVICE_ID %d", st>>5)
	}
	if err := d.writeMode(DRV2605ModeInternalTrigger, false); err != nil {
		return nil, err
	}
	feedback, library := uint8(0x36), uint8(0x01)
	if lra {
		feedback, library = 0xB6, 0x06
	}
	if err := d.writeReg(drv2605RegFeedback, feedback); err != nil {
		return nil, err
	}
	if err := d.writeReg(drv2605RegLibrary, library); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *DRV2605Minimal) readReg(reg uint8) (uint8, error) {
	b, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *DRV2605Minimal) writeReg(reg, val uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{val})
}

// updateReg replaces the bits in mask with val (already shifted) via read-modify-write.
func (d *DRV2605Minimal) updateReg(reg, mask, val uint8) error {
	old, err := d.readReg(reg)
	if err != nil {
		return err
	}
	return d.writeReg(reg, old&^mask|val&mask)
}

func (d *DRV2605Minimal) writeMode(mode uint8, standby bool) error {
	v := mode
	if standby {
		v |= drv2605ModeStandby
	}
	if err := d.writeReg(drv2605RegMode, v); err != nil {
		return err
	}
	d.mode = mode
	d.standby = standby
	return nil
}

func (d *DRV2605Minimal) resetChip() error {
	if err := d.writeReg(drv2605RegMode, drv2605ModeDevReset); err != nil {
		return err
	}
	d.mode = DRV2605ModeInternalTrigger
	d.standby = true
	time.Sleep(2 * time.Millisecond)
	for i := 0; i < 100; i++ {
		v, err := d.readReg(drv2605RegMode)
		if err != nil {
			return err
		}
		if v&drv2605ModeDevReset == 0 {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrDRV2605Timeout
}

func (d *DRV2605Minimal) ensureInternal() error {
	if d.mode != DRV2605ModeInternalTrigger || d.standby {
		return d.writeMode(DRV2605ModeInternalTrigger, false)
	}
	return nil
}

// PlayEffect plays one ROM effect (1-123) and returns immediately. It writes
// the effect to sequencer slot 1 and 0 (end) to slot 2, switches to
// internal-trigger mode if needed, then sets GO.
func (d *DRV2605Minimal) PlayEffect(effectID uint8) error {
	if effectID < 1 || effectID > 123 {
		return ErrDRV2605InvalidArgument
	}
	if err := d.ensureInternal(); err != nil {
		return err
	}
	if err := d.conn.WriteReg(uint32(drv2605RegSeq1), []byte{effectID, 0}); err != nil {
		return err
	}
	return d.writeReg(drv2605RegGo, 1)
}

// Stop cancels playback by clearing the GO bit.
func (d *DRV2605Minimal) Stop() error { return d.writeReg(drv2605RegGo, 0) }

// IsPlaying reports whether a sequence is playing (GO bit set).
func (d *DRV2605Minimal) IsPlaying() (bool, error) {
	v, err := d.readReg(drv2605RegGo)
	return v&0x01 != 0, err
}

// DRV2605Full extends DRV2605Minimal with the waveform sequencer, real-time
// playback, mode and library control, drive configuration, auto-calibration,
// diagnostics, audio-to-vibe, status readout and one-time OTP programming.
type DRV2605Full struct {
	DRV2605Minimal
}

// NewDRV2605Full creates a DRV2605Full; it runs the same initialization as NewDRV2605Minimal.
func NewDRV2605Full(conn connection.RegisterConnection, lra bool) (*DRV2605Full, error) {
	m, err := NewDRV2605Minimal(conn, lra)
	if err != nil {
		return nil, err
	}
	return &DRV2605Full{DRV2605Minimal: *m}, nil
}

// Stop cancels playback; it also leaves real-time-playback mode (output to zero).
func (d *DRV2605Full) Stop() error {
	if err := d.DRV2605Minimal.Stop(); err != nil {
		return err
	}
	if d.mode == DRV2605ModeRTP {
		if err := d.writeReg(drv2605RegRTPInput, 0); err != nil {
			return err
		}
		return d.writeMode(DRV2605ModeInternalTrigger, d.standby)
	}
	return nil
}

// PlaySequence plays up to 8 effects and delays. A wait step is 0-1270 ms in
// 10 ms steps; the list is terminated with 0 if shorter than 8.
func (d *DRV2605Full) PlaySequence(items []DRV2605SequenceStep) error {
	if len(items) < 1 || len(items) > 8 {
		return ErrDRV2605InvalidArgument
	}
	data := make([]byte, 0, 8)
	for _, it := range items {
		if it.Wait {
			if it.Value > 1270 {
				return ErrDRV2605InvalidArgument
			}
			data = append(data, 0x80|byte((it.Value+5)/10))
		} else {
			if it.Value < 1 || it.Value > 123 {
				return ErrDRV2605InvalidArgument
			}
			data = append(data, byte(it.Value))
		}
	}
	if len(data) < 8 {
		data = append(data, 0)
	}
	if err := d.ensureInternal(); err != nil {
		return err
	}
	if err := d.conn.WriteReg(uint32(drv2605RegSeq1), data); err != nil {
		return err
	}
	return d.writeReg(drv2605RegGo, 1)
}

// WaitDone polls GO until playback finishes; it returns false on timeout.
func (d *DRV2605Full) WaitDone(timeoutMs int) (bool, error) {
	waited := 0
	for {
		playing, err := d.IsPlaying()
		if err != nil {
			return false, err
		}
		if !playing {
			return true, nil
		}
		if waited >= timeoutMs {
			return false, nil
		}
		time.Sleep(2 * time.Millisecond)
		waited += 2
	}
}

// SetLibrary selects the ROM library: 0 empty, 1-5 ERM libraries A-E, 6 LRA library.
func (d *DRV2605Full) SetLibrary(library uint8) error {
	if library > 6 {
		return ErrDRV2605InvalidArgument
	}
	return d.updateReg(drv2605RegLibrary, 0x07, library)
}

// SetMode sets MODE<2:0> (0-7) and leaves standby.
func (d *DRV2605Full) SetMode(mode uint8) error {
	if mode > 7 {
		return ErrDRV2605InvalidArgument
	}
	return d.writeMode(mode, false)
}

// Standby enters software standby (also stops playback).
func (d *DRV2605Full) Standby() error { return d.writeMode(d.mode, true) }

// Wake leaves software standby.
func (d *DRV2605Full) Wake() error { return d.writeMode(d.mode, false) }

// Reset issues DEV_RESET and waits for completion; re-create the driver afterwards.
func (d *DRV2605Full) Reset() error { return d.resetChip() }

// SetHiZ puts the outputs in true high-impedance state (HI_Z).
func (d *DRV2605Full) SetHiZ(enabled bool) error {
	var v uint8
	if enabled {
		v = 0x10
	}
	return d.updateReg(drv2605RegLibrary, 0x10, v)
}

func (d *DRV2605Full) isLRA() (bool, error) {
	v, err := d.readReg(drv2605RegFeedback)
	return v&0x80 != 0, err
}

func (d *DRV2605Full) isOpenLoop(lra bool) (bool, error) {
	v, err := d.readReg(drv2605RegControl3)
	mask := uint8(0x20)
	if lra {
		mask = 0x01
	}
	return v&mask != 0, err
}

// SetActuator selects ERM (false) or LRA (true) in N_ERM_LRA; run Calibrate afterwards.
func (d *DRV2605Full) SetActuator(lra bool) error {
	var v uint8
	if lra {
		v = 0x80
	}
	return d.updateReg(drv2605RegFeedback, 0x80, v)
}

// SetOpenLoop enables open-loop drive (ERM_OPEN_LOOP or LRA_OPEN_LOOP according to the actuator type).
func (d *DRV2605Full) SetOpenLoop(enabled bool) error {
	lra, err := d.isLRA()
	if err != nil {
		return err
	}
	mask := uint8(0x20)
	if lra {
		mask = 0x01
	}
	var v uint8
	if enabled {
		v = mask
	}
	return d.updateReg(drv2605RegControl3, mask, v)
}

// SetBidirectional selects bidirectional (true) or unidirectional (false) input.
// Unidirectional is closed-loop only and returns ErrDRV2605InvalidState while open-loop.
func (d *DRV2605Full) SetBidirectional(enabled bool) error {
	if !enabled {
		lra, err := d.isLRA()
		if err != nil {
			return err
		}
		open, err := d.isOpenLoop(lra)
		if err != nil {
			return err
		}
		if open {
			return ErrDRV2605InvalidState
		}
	}
	var v uint8
	if enabled {
		v = 0x80
	}
	return d.updateReg(drv2605RegControl2, 0x80, v)
}

func (d *DRV2605Full) enterRTP() error {
	if d.mode != DRV2605ModeRTP || d.standby {
		return d.writeMode(DRV2605ModeRTP, false)
	}
	return nil
}

// RtpLevel streams a signed real-time-playback amplitude (0.0-1.0 maps to 0-127).
// It enters RTP mode if not already there; leave with Stop, SetMode or Standby.
func (d *DRV2605Full) RtpLevel(level float32) error {
	if !(level >= 0 && level <= 1) {
		return ErrDRV2605InvalidArgument
	}
	if d.mode != DRV2605ModeRTP {
		if err := d.updateReg(drv2605RegControl3, 0x08, 0); err != nil {
			return err
		}
	}
	if err := d.enterRTP(); err != nil {
		return err
	}
	return d.writeReg(drv2605RegRTPInput, uint8(level*127+0.5))
}

// RtpWrite writes a raw RTP_INPUT byte, interpreted per the RTP data format, and enters RTP mode.
func (d *DRV2605Full) RtpWrite(value uint8) error {
	if err := d.enterRTP(); err != nil {
		return err
	}
	return d.writeReg(drv2605RegRTPInput, value)
}

// SetRtpFormat selects unsigned (true) or signed (false) RTP data (DATA_FORMAT_RTP).
func (d *DRV2605Full) SetRtpFormat(unsigned bool) error {
	var v uint8
	if unsigned {
		v = 0x08
	}
	return d.updateReg(drv2605RegControl3, 0x08, v)
}

// SetInput selects analog (true) or PWM (false) input for mode 3 (N_PWM_ANALOG).
func (d *DRV2605Full) SetInput(analog bool) error {
	var v uint8
	if analog {
		v = 0x02
	}
	return d.updateReg(drv2605RegControl3, 0x02, v)
}

func (d *DRV2605Full) voltsToRatedCode(volts, lraFreqHz float32) (uint8, error) {
	lra, err := d.isLRA()
	if err != nil {
		return 0, err
	}
	if !lra {
		return drv2605ClampCode(volts / 21.33e-3), nil
	}
	if !(lraFreqHz > 0) {
		return 0, ErrDRV2605InvalidArgument
	}
	c2, err := d.readReg(drv2605RegControl2)
	if err != nil {
		return 0, err
	}
	factor := 1 - (4*drv2605SampleTimeS[(c2>>4)&0x03]+300e-6)*lraFreqHz
	if factor <= 0 {
		return 0, ErrDRV2605InvalidArgument
	}
	return drv2605ClampCode(volts * drv2605Sqrt(factor) / 20.71e-3), nil
}

// SetRatedVoltage sets RATED_VOLTAGE from volts (RMS for an LRA). lraFreqHz is
// required (> 0) for an LRA and ignored for an ERM.
func (d *DRV2605Full) SetRatedVoltage(volts, lraFreqHz float32) error {
	code, err := d.voltsToRatedCode(volts, lraFreqHz)
	if err != nil {
		return err
	}
	return d.writeReg(drv2605RegRatedVoltage, code)
}

// SetRatedVoltageCode sets RATED_VOLTAGE from a raw code.
func (d *DRV2605Full) SetRatedVoltageCode(code uint8) error {
	return d.writeReg(drv2605RegRatedVoltage, code)
}

// SetOverdriveClamp sets OD_CLAMP from volts (RMS for LRA open loop). lraFreqHz
// is required (> 0) for LRA open loop. ERM closed loop returns
// ErrDRV2605InvalidState (it needs undocumented timing values; use SetOverdriveClampCode).
func (d *DRV2605Full) SetOverdriveClamp(volts, lraFreqHz float32) error {
	lra, err := d.isLRA()
	if err != nil {
		return err
	}
	open, err := d.isOpenLoop(lra)
	if err != nil {
		return err
	}
	var code uint8
	switch {
	case lra && open:
		if !(lraFreqHz > 0) {
			return ErrDRV2605InvalidArgument
		}
		factor := 1 - lraFreqHz*800e-6
		if factor <= 0 {
			return ErrDRV2605InvalidArgument
		}
		code = drv2605ClampCode(volts / (21.33e-3 * drv2605Sqrt(factor)))
	case !lra && !open:
		return ErrDRV2605InvalidState
	default:
		code = drv2605ClampCode(volts / 21.96e-3)
	}
	return d.writeReg(drv2605RegODClamp, code)
}

// SetOverdriveClampCode sets OD_CLAMP from a raw code.
func (d *DRV2605Full) SetOverdriveClampCode(code uint8) error {
	return d.writeReg(drv2605RegODClamp, code)
}

// SetDriveTime sets DRIVE_TIME in ms (LRA: half the resonance period; ERM: back-EMF sample period).
func (d *DRV2605Full) SetDriveTime(ms float32) error {
	lra, err := d.isLRA()
	if err != nil {
		return err
	}
	return d.updateReg(drv2605RegControl1, 0x1F, drv2605DriveTimeCode(ms, lra))
}

// SetFeedback sets the FEEDBACK_CONTROL fields (brakeFactor 0-7, loopGain 0-3,
// bemfGain 0-3); the actuator-type bit is preserved.
func (d *DRV2605Full) SetFeedback(brakeFactor, loopGain, bemfGain uint8) error {
	if brakeFactor > 7 || loopGain > 3 || bemfGain > 3 {
		return ErrDRV2605InvalidArgument
	}
	return d.updateReg(drv2605RegFeedback, 0x7F, brakeFactor<<4|loopGain<<2|bemfGain)
}

func drv2605Bit(b bool, v uint8) uint8 {
	if b {
		return v
	}
	return 0
}

// SetAdvanced sets the CONTROL1-CONTROL3 fields not covered by other setters.
func (d *DRV2605Full) SetAdvanced(cfg DRV2605AdvancedConfig) error {
	if cfg.SampleTime > 3 || cfg.BlankingTime > 3 || cfg.IdissTime > 3 || cfg.NoiseGate > 3 {
		return ErrDRV2605InvalidArgument
	}
	if err := d.updateReg(drv2605RegControl1, 0x80, drv2605Bit(cfg.StartupBoost, 0x80)); err != nil {
		return err
	}
	if err := d.updateReg(drv2605RegControl2, 0x7F,
		drv2605Bit(cfg.BrakeStabilizer, 0x40)|cfg.SampleTime<<4|cfg.BlankingTime<<2|cfg.IdissTime); err != nil {
		return err
	}
	return d.updateReg(drv2605RegControl3, 0xD4,
		cfg.NoiseGate<<6|drv2605Bit(!cfg.SupplyComp, 0x10)|drv2605Bit(cfg.LRADriveTwice, 0x04))
}

// SetTimeOffsets sets the ODT, SPT, SNT, BRT offsets in ms (5 ms steps, signed
// 8-bit: -640..635 ms).
func (d *DRV2605Full) SetTimeOffsets(overdriveMs, sustainPosMs, sustainNegMs, brakeMs int) error {
	vals := [4]int{overdriveMs, sustainPosMs, sustainNegMs, brakeMs}
	data := make([]byte, 4)
	for i, ms := range vals {
		var steps int
		if ms >= 0 {
			steps = (ms + 2) / 5
		} else {
			steps = -((-ms + 2) / 5)
		}
		if steps < -128 || steps > 127 {
			return ErrDRV2605InvalidArgument
		}
		data[i] = byte(int8(steps))
	}
	return d.conn.WriteReg(uint32(drv2605RegODT), data)
}

// Calibrate runs the auto-calibration procedure. overdriveVoltage uses the
// open-loop conversion (V / 21.96 mV) for an ERM, since the closed-loop
// correction needs undocumented timing values. lraFreqHz is required (> 0) for
// an LRA and also sets DRIVE_TIME to half the period. The standby state is
// restored afterwards.
func (d *DRV2605Full) Calibrate(lra bool, ratedVoltage, overdriveVoltage, lraFreqHz float32) (DRV2605Calibration, error) {
	var res DRV2605Calibration
	if lra && !(lraFreqHz > 0) {
		return res, ErrDRV2605InvalidArgument
	}
	wasStandby := d.standby
	if err := d.writeMode(DRV2605ModeAutoCalibration, false); err != nil {
		return res, err
	}
	fb, err := d.readReg(drv2605RegFeedback)
	if err != nil {
		return res, err
	}
	if err := d.writeReg(drv2605RegFeedback, drv2605Bit(lra, 0x80)|2<<4|2<<2|fb&0x03); err != nil {
		return res, err
	}
	// SAMPLE_TIME = 3, BLANKING_TIME = 1, IDISS_TIME = 1
	if err := d.updateReg(drv2605RegControl2, 0x3F, 3<<4|1<<2|1); err != nil {
		return res, err
	}
	if lra {
		if err := d.updateReg(drv2605RegControl1, 0x1F, drv2605DriveTimeCode(500/lraFreqHz, true)); err != nil {
			return res, err
		}
	}
	rated, err := d.voltsToRatedCode(ratedVoltage, lraFreqHz)
	if err != nil {
		_ = d.writeMode(DRV2605ModeInternalTrigger, wasStandby)
		return res, err
	}
	if err := d.writeReg(drv2605RegRatedVoltage, rated); err != nil {
		return res, err
	}
	if err := d.writeReg(drv2605RegODClamp, drv2605ClampCode(overdriveVoltage/21.96e-3)); err != nil {
		return res, err
	}
	// AUTO_CAL_TIME = 3; OTP_PROGRAM stays 0
	if err := d.updateReg(drv2605RegControl4, 0x31, 3<<4); err != nil {
		return res, err
	}
	if err := d.writeReg(drv2605RegGo, 1); err != nil {
		return res, err
	}
	finished, err := d.WaitDone(3000)
	if err != nil {
		return res, err
	}
	st, err := d.readReg(drv2605RegStatus)
	if err != nil {
		return res, err
	}
	res, err = d.GetCalibration()
	if err != nil {
		return res, err
	}
	res.OK = finished && st&0x08 == 0
	return res, d.writeMode(DRV2605ModeInternalTrigger, wasStandby)
}

// GetCalibration reads the stored A_CAL_COMP, A_CAL_BEMF and BEMF_GAIN (OK is always true).
func (d *DRV2605Full) GetCalibration() (DRV2605Calibration, error) {
	b, err := d.conn.ReadReg(uint32(drv2605RegACalComp), 2)
	if err != nil {
		return DRV2605Calibration{}, err
	}
	fb, err := d.readReg(drv2605RegFeedback)
	if err != nil {
		return DRV2605Calibration{}, err
	}
	return DRV2605Calibration{OK: true, Comp: b[0], BEMF: b[1], BEMFGain: fb & 0x03}, nil
}

// SetCalibration restores A_CAL_COMP, A_CAL_BEMF and BEMF_GAIN (0-3) from an earlier calibration.
func (d *DRV2605Full) SetCalibration(comp, bemf, bemfGain uint8) error {
	if bemfGain > 3 {
		return ErrDRV2605InvalidArgument
	}
	if err := d.conn.WriteReg(uint32(drv2605RegACalComp), []byte{comp, bemf}); err != nil {
		return err
	}
	return d.updateReg(drv2605RegFeedback, 0x03, bemfGain)
}

// Diagnose runs the actuator diagnostics; it returns true if the actuator is OK (DIAG_RESULT = 0).
func (d *DRV2605Full) Diagnose() (bool, error) {
	wasStandby := d.standby
	if err := d.writeMode(DRV2605ModeDiagnostics, false); err != nil {
		return false, err
	}
	if err := d.writeReg(drv2605RegGo, 1); err != nil {
		return false, err
	}
	finished, err := d.WaitDone(3000)
	if err != nil {
		return false, err
	}
	st, err := d.readReg(drv2605RegStatus)
	if err != nil {
		return false, err
	}
	if err := d.writeMode(DRV2605ModeInternalTrigger, wasStandby); err != nil {
		return false, err
	}
	return finished && st&0x08 == 0, nil
}

// SetAudioToVibe configures the audio-to-vibe engine and enters audio-to-vibe mode.
func (d *DRV2605Full) SetAudioToVibe(cfg DRV2605AudioToVibeConfig) error {
	peak, filter := -1, -1
	for i := 0; i < 4; i++ {
		if drv2605PeakTimesMs[i] == cfg.PeakTimeMs {
			peak = i
		}
		if drv2605FiltersHz[i] == cfg.FilterHz {
			filter = i
		}
	}
	if peak < 0 || filter < 0 ||
		!(cfg.MinInputV >= 0 && cfg.MinInputV <= 1.8 && cfg.MaxInputV >= 0 && cfg.MaxInputV <= 1.8 &&
			cfg.MinDrive >= 0 && cfg.MinDrive <= 1 && cfg.MaxDrive >= 0 && cfg.MaxDrive <= 1) {
		return ErrDRV2605InvalidArgument
	}
	data := []byte{
		byte(peak<<2 | filter),
		drv2605ClampCode(cfg.MinInputV * 255 / 1.8), drv2605ClampCode(cfg.MaxInputV * 255 / 1.8),
		drv2605ClampCode(cfg.MinDrive * 255), drv2605ClampCode(cfg.MaxDrive * 255),
	}
	if err := d.conn.WriteReg(uint32(drv2605RegATHCtrl), data); err != nil {
		return err
	}
	if err := d.updateReg(drv2605RegControl1, 0x20, drv2605Bit(cfg.ACCouple, 0x20)); err != nil {
		return err
	}
	if err := d.updateReg(drv2605RegControl3, 0x02, 0x02); err != nil {
		return err
	}
	return d.writeMode(DRV2605ModeAudioToVibe, false)
}

// ReadStatus reads STATUS, which clears the latched DIAG_RESULT, FB_STS and OVER_TEMP flags.
func (d *DRV2605Full) ReadStatus() (DRV2605Status, error) {
	s, err := d.readReg(drv2605RegStatus)
	if err != nil {
		return DRV2605Status{}, err
	}
	return DRV2605Status{DiagFailed: s&0x08 != 0, FBTimeout: s&0x04 != 0, OverTemp: s&0x02 != 0, OverCurrent: s&0x01 != 0}, nil
}

// ReadDeviceID returns DEVICE_ID (3 for the DRV2605). It clears the latched status flags.
func (d *DRV2605Full) ReadDeviceID() (uint8, error) {
	s, err := d.readReg(drv2605RegStatus)
	return s >> 5, err
}

// ReadSupplyVoltage returns VDD in V from VBAT; valid only during playback.
func (d *DRV2605Full) ReadSupplyVoltage() (float32, error) {
	v, err := d.readReg(drv2605RegVBat)
	return float32(v) * 5.6 / 255, err
}

// ReadLRAFrequency returns the LRA resonance frequency in Hz from LRA_PERIOD;
// valid only during LRA playback. ok is false if the register is 0.
func (d *DRV2605Full) ReadLRAFrequency() (hz float32, ok bool, err error) {
	v, err := d.readReg(drv2605RegLRAPeriod)
	if err != nil || v == 0 {
		return 0, false, err
	}
	return 1e6 / (float32(v) * 98.46), true, nil
}

// ProgramOTP burns registers 0x16-0x1A into OTP. It is irreversible and can be
// run exactly once; VDD must be 4.0-4.4 V. confirm must be true. It returns
// ErrDRV2605InvalidState if the OTP is already programmed and ErrDRV2605Timeout
// if programming does not complete.
func (d *DRV2605Full) ProgramOTP(confirm bool) error {
	if !confirm {
		return ErrDRV2605InvalidArgument
	}
	done, err := d.OTPProgrammed()
	if err != nil {
		return err
	}
	if done {
		return ErrDRV2605InvalidState
	}
	if err := d.updateReg(drv2605RegControl4, 0x01, 0x01); err != nil {
		return err
	}
	for i := 0; i < 100; i++ {
		time.Sleep(10 * time.Millisecond)
		done, err := d.OTPProgrammed()
		if err != nil {
			return err
		}
		if done {
			return nil
		}
	}
	return ErrDRV2605Timeout
}

// OTPProgrammed reports OTP_STATUS: true if the OTP has already been programmed.
func (d *DRV2605Full) OTPProgrammed() (bool, error) {
	v, err := d.readReg(drv2605RegControl4)
	return v&0x04 != 0, err
}

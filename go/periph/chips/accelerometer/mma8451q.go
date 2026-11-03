// MMA8451Q driver — NXP MMA8451Q 3-axis accelerometer.
//
// The chip is register-addressed over I2C (plain register-pointer protocol,
// no auto-increment bit). Every configuration register except CTRL_REG1's
// ACTIVE bit and CTRL_REG2's RST bit may only be written while the device is
// in STANDBY; the Full driver wraps those writes internally.
package accelerometer

import (
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MMA8451Q register addresses.
const (
	mma8451qRegStatus      uint8 = 0x00 // STATUS (F_MODE=0) / F_STATUS (F_MODE>0)
	mma8451qRegOutXMSB     uint8 = 0x01
	mma8451qRegOutXLSB     uint8 = 0x02
	mma8451qRegOutYMSB     uint8 = 0x03
	mma8451qRegOutYLSB     uint8 = 0x04
	mma8451qRegOutZMSB     uint8 = 0x05
	mma8451qRegOutZLSB     uint8 = 0x06
	mma8451qRegFSetup      uint8 = 0x09
	mma8451qRegTrigCfg     uint8 = 0x0A
	mma8451qRegSysMod      uint8 = 0x0B
	mma8451qRegIntSource   uint8 = 0x0C
	mma8451qRegWhoAmI      uint8 = 0x0D
	mma8451qRegXYZDataCfg  uint8 = 0x0E
	mma8451qRegHPFilterCut uint8 = 0x0F
	mma8451qRegPLStatus    uint8 = 0x10
	mma8451qRegPLCfg       uint8 = 0x11
	mma8451qRegPLCount     uint8 = 0x12
	mma8451qRegPLBFZComp   uint8 = 0x13
	mma8451qRegPLThsReg    uint8 = 0x14
	mma8451qRegFFMTCfg     uint8 = 0x15
	mma8451qRegFFMTSrc     uint8 = 0x16
	mma8451qRegFFMTThs     uint8 = 0x17
	mma8451qRegFFMTCount   uint8 = 0x18
	mma8451qRegTransientC  uint8 = 0x1D
	mma8451qRegTransientS  uint8 = 0x1E
	mma8451qRegTransientT  uint8 = 0x1F
	mma8451qRegTransientC2 uint8 = 0x20
	mma8451qRegPulseCfg    uint8 = 0x21
	mma8451qRegPulseSrc    uint8 = 0x22
	mma8451qRegPulseThsX   uint8 = 0x23
	mma8451qRegPulseThsY   uint8 = 0x24
	mma8451qRegPulseThsZ   uint8 = 0x25
	mma8451qRegPulseTMLT   uint8 = 0x26
	mma8451qRegPulseLTCY   uint8 = 0x27
	mma8451qRegPulseWIND   uint8 = 0x28
	mma8451qRegASLPCount   uint8 = 0x29
	mma8451qRegCtrlReg1    uint8 = 0x2A
	mma8451qRegCtrlReg2    uint8 = 0x2B
	mma8451qRegCtrlReg3    uint8 = 0x2C
	mma8451qRegCtrlReg4    uint8 = 0x2D
	mma8451qRegCtrlReg5    uint8 = 0x2E
	mma8451qRegOffX        uint8 = 0x2F
	mma8451qRegOffY        uint8 = 0x30
	mma8451qRegOffZ        uint8 = 0x31
)

// mma8451qWhoAmIValue is the expected content of the WHO_AM_I register.
const mma8451qWhoAmIValue uint8 = 0x1A

// CTRL_REG1 bit masks.
const (
	mma8451qCtrl1AslpRate uint8 = 0xC0 // bits 7:6
	mma8451qCtrl1DR       uint8 = 0x38 // bits 5:3
	mma8451qCtrl1LNoise   uint8 = 0x04 // bit 2
	mma8451qCtrl1FRead    uint8 = 0x02 // bit 1
	mma8451qCtrl1Active   uint8 = 0x01 // bit 0
)

// CTRL_REG2 bit masks.
const (
	mma8451qCtrl2ST    uint8 = 0x80 // bit 7
	mma8451qCtrl2RST   uint8 = 0x40 // bit 6
	mma8451qCtrl2SMODS uint8 = 0x18 // bits 4:3
	mma8451qCtrl2SLPE  uint8 = 0x04 // bit 2
	mma8451qCtrl2MODS  uint8 = 0x03 // bits 1:0
)

// CTRL_REG3 bit masks.
const (
	mma8451qCtrl3FifoGate   uint8 = 0x80 // bit 7
	mma8451qCtrl3WakeTrans  uint8 = 0x40 // bit 6
	mma8451qCtrl3WakeLndPrt uint8 = 0x20 // bit 5
	mma8451qCtrl3WakePulse  uint8 = 0x10 // bit 4
	mma8451qCtrl3WakeFFMT   uint8 = 0x08 // bit 3
	mma8451qCtrl3IPol       uint8 = 0x02 // bit 1
	mma8451qCtrl3PPOD       uint8 = 0x01 // bit 0
)

// Interrupt source bits — the same bit positions are used by INT_SOURCE,
// CTRL_REG4 (INT_EN_*) and CTRL_REG5 (INT_CFG_*).
const (
	// MMA8451QSourceDataReady fires when a new XYZ sample is available.
	MMA8451QSourceDataReady uint8 = 0x01
	// MMA8451QSourceFFMT is the freefall / motion detector.
	MMA8451QSourceFFMT uint8 = 0x04
	// MMA8451QSourcePulse is the single / double tap detector.
	MMA8451QSourcePulse uint8 = 0x08
	// MMA8451QSourceOrientation is the portrait / landscape detector.
	MMA8451QSourceOrientation uint8 = 0x10
	// MMA8451QSourceTransient is the transient (high-pass motion) detector.
	MMA8451QSourceTransient uint8 = 0x20
	// MMA8451QSourceFIFO is the FIFO watermark / overflow event.
	MMA8451QSourceFIFO uint8 = 0x40
	// MMA8451QSourceAutoSleep reports a WAKE <-> SLEEP transition.
	MMA8451QSourceAutoSleep uint8 = 0x80
)

// MMA8451QSystemMode values returned by ReadSystemMode.
const (
	// MMA8451QModeStandby is the STANDBY system mode.
	MMA8451QModeStandby uint8 = 0
	// MMA8451QModeWake is the WAKE (active) system mode.
	MMA8451QModeWake uint8 = 1
	// MMA8451QModeSleep is the SLEEP system mode.
	MMA8451QModeSleep uint8 = 2
)

// MMA8451QThresholdLSB is the threshold resolution in *g* per LSB
// (0.063 g/LSB) used by FF_MT_THS, TRANSIENT_THS and PULSE_THSx.
const MMA8451QThresholdLSB float32 = 0.063

// mma8451qOffsetLSB is the offset register resolution in *g* per LSB.
const mma8451qOffsetLSB float32 = 0.002

// mma8451qSelfTestTypical holds the datasheet's typical self-test deltas in
// 14-bit counts (X, Y, Z). The datasheet lists no limits, so the pass
// criterion is "more than half the typical value on every axis".
var mma8451qSelfTestTypical = [3]int32{181, 255, 1680}

// mma8451qODRTable lists the supported output data rates with their DR code
// (CTRL_REG1 bits 5:3) and the debounce step in milliseconds for each
// oversampling mode (normal, low-noise low-power, high resolution, low power).
var mma8451qODRTable = []struct {
	hz      float32
	code    uint8
	debStep [4]float32
}{
	{800, 0x00, [4]float32{1.25, 1.25, 1.25, 1.25}},
	{400, 0x08, [4]float32{2.5, 2.5, 2.5, 2.5}},
	{200, 0x10, [4]float32{5, 5, 2.5, 5}},
	{100, 0x18, [4]float32{10, 10, 2.5, 10}},
	{50, 0x20, [4]float32{20, 20, 2.5, 20}},
	{12.5, 0x28, [4]float32{20, 80, 2.5, 80}},
	{6.25, 0x30, [4]float32{20, 80, 2.5, 160}},
	{1.56, 0x38, [4]float32{20, 80, 2.5, 160}},
}

// mma8451qPulseTMLTStep lists the PULSE_TMLT step in milliseconds per ODR for
// each oversampling mode. Index: ODR table index, then mode (normal,
// low-noise low-power, high resolution, low power).
var mma8451qPulseTMLTStep = [8][4]float32{
	{1.25, 1.25, 1.25, 1.25},
	{2.5, 2.5, 2.5, 2.5},
	{5, 5, 2.5, 5},
	{10, 10, 2.5, 10},
	{20, 20, 2.5, 20},
	{20, 80, 2.5, 80},
	{20, 80, 2.5, 160},
	{20, 80, 2.5, 160},
}

// mma8451qPulseLTCYStep lists the PULSE_LTCY / PULSE_WIND step in
// milliseconds per ODR for each oversampling mode.
var mma8451qPulseLTCYStep = [8][4]float32{
	{2.5, 2.5, 2.5, 2.5},
	{5, 5, 5, 5},
	{10, 10, 5, 10},
	{20, 20, 5, 20},
	{40, 40, 5, 40},
	{40, 160, 5, 160},
	{40, 160, 5, 320},
	{40, 160, 5, 320},
}

// mma8451qPulseTMLTStepNoLPF is the PULSE_TMLT step table used when the pulse
// low-pass filter is disabled (Pulse_LPF_EN = 0).
var mma8451qPulseTMLTStepNoLPF = [8][4]float32{
	{0.625, 0.625, 0.625, 0.625},
	{0.625, 0.625, 0.625, 1.25},
	{1.25, 1.25, 0.625, 2.5},
	{2.5, 2.5, 0.625, 5},
	{5, 5, 0.625, 10},
	{5, 20, 0.625, 40},
	{5, 20, 0.625, 40},
	{5, 20, 0.625, 40},
}

// mma8451qPulseLTCYStepNoLPF is the PULSE_LTCY / PULSE_WIND step table used
// when the pulse low-pass filter is disabled (Pulse_LPF_EN = 0).
var mma8451qPulseLTCYStepNoLPF = [8][4]float32{
	{1.25, 1.25, 1.25, 1.25},
	{1.25, 1.25, 1.25, 2.5},
	{2.5, 2.5, 1.25, 5},
	{5, 5, 1.25, 10},
	{10, 10, 1.25, 20},
	{10, 40, 1.25, 80},
	{10, 40, 1.25, 80},
	{10, 40, 1.25, 80},
}

// mma8451qOrientationThresholds maps the discrete portrait / landscape trip
// angles to their P_L_THS register code (datasheet Table 32).
var mma8451qOrientationThresholds = map[int]uint8{
	15: 0x07,
	20: 0x09,
	30: 0x0C,
	35: 0x0D,
	40: 0x0F,
	45: 0x10,
	55: 0x13,
	60: 0x14,
	70: 0x17,
	75: 0x19,
}

// MMA8451QMinimal is the MMA8451Q 3-axis accelerometer — minimal interface.
//
// Reads X, Y, Z acceleration in *g* with sensible defaults; no configuration
// is required beyond the connection.
//
// Default configuration (baked in at construction):
// - Range ±2 *g* (4096 counts/g)
// - Output data rate 100 Hz
// - 14-bit output (F_READ = 0)
// - Normal oversampling in WAKE and SLEEP
// - Low-noise mode off, high-pass output off
// - FIFO disabled, all detectors and interrupt routing off
// - Interrupts active-low, push-pull
type MMA8451QMinimal struct {
	conn connection.RegisterConnection

	rangeG      uint8 // 2, 4 or 8
	odrHz       float32
	odrIndex    int
	bits        uint8 // 14 or 8
	oversample  uint8 // MODS
	sleepMode   uint8 // SMODS
	lowNoise    bool
	fifoMode    uint8
	fifoEnabled bool
}

// NewMMA8451QMinimal creates an MMA8451QMinimal and runs the chip's
// initialization sequence: verify WHO_AM_I, software-reset the device,
// configure ±2 *g* at 100 Hz in 14-bit normal-oversampling mode and switch it
// to ACTIVE.
//
// Returns an error if WHO_AM_I does not read 0x1A.
func NewMMA8451QMinimal(conn connection.RegisterConnection) (*MMA8451QMinimal, error) {
	c := &MMA8451QMinimal{
		conn:       conn,
		rangeG:     2,
		odrHz:      100,
		odrIndex:   3,
		bits:       14,
		oversample: 0,
		sleepMode:  0,
	}
	if err := c.init(); err != nil {
		return nil, err
	}
	return c, nil
}

// init runs the chip's power-up / reset sequence.
func (c *MMA8451QMinimal) init() error {
	// 1. The I2C interface needs ~350 us after power-up; wait a comfortable
	//    1 ms before the first transaction.
	time.Sleep(time.Millisecond)

	// 2. Verify the identity register.
	id, err := c.readReg8(mma8451qRegWhoAmI)
	if err != nil {
		return err
	}
	if id != mma8451qWhoAmIValue {
		return fmt.Errorf("MMA8451Q WHO_AM_I: expected 0x%02X, got 0x%02X",
			mma8451qWhoAmIValue, id)
	}

	// 3. Software reset. The reset also resets the I2C interface, so the
	//    write may NACK; retry until CTRL_REG2 reads back 0 (10 ms budget).
	if err := c.reset(); err != nil {
		return err
	}

	// 4. ±2 g, no high-pass output.
	if err := c.writeReg(mma8451qRegXYZDataCfg, 0x00); err != nil {
		return err
	}
	// 5. Normal oversampling in WAKE and SLEEP, Auto-SLEEP off.
	if err := c.writeReg(mma8451qRegCtrlReg2, 0x00); err != nil {
		return err
	}
	// 6. DR = 011 (100 Hz), LNOISE = 0, F_READ = 0, ACTIVE = 1.
	if err := c.writeReg(mma8451qRegCtrlReg1, 0x19); err != nil {
		return err
	}
	// 7. Wait for the STANDBY -> ACTIVE turn-on time (2/ODR + 2 ms).
	c.waitTurnOn()
	return nil
}

// reset performs the CTRL_REG2.RST software reset and waits for the device to
// come back with all registers at their reset values.
func (c *MMA8451QMinimal) reset() error {
	if err := c.writeReg(mma8451qRegCtrlReg2, mma8451qCtrl2RST); err != nil {
		// The reset may NACK the data phase — that is expected.
		_ = err
	}
	time.Sleep(time.Millisecond)
	deadline := time.Now().Add(10 * time.Millisecond)
	for {
		v, err := c.readReg8(mma8451qRegCtrlReg2)
		if err == nil && v == 0x00 {
			return nil
		}
		if time.Now().After(deadline) {
			if err != nil {
				return err
			}
			return fmt.Errorf("MMA8451Q reset: CTRL_REG2 = 0x%02X, want 0x00", v)
		}
		time.Sleep(time.Millisecond)
	}
}

func (c *MMA8451QMinimal) writeReg(reg, val uint8) error {
	return c.conn.WriteReg(uint32(reg), []byte{val})
}

func (c *MMA8451QMinimal) readReg8(reg uint8) (uint8, error) {
	b, err := c.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (c *MMA8451QMinimal) readBurst(reg uint8, n int) ([]byte, error) {
	return c.conn.ReadReg(uint32(reg), n)
}

// countsPerG returns the number of counts per *g* for the current range and
// resolution.
func (c *MMA8451QMinimal) countsPerG() float32 {
	var base float32
	switch c.rangeG {
	case 4:
		base = 2048
	case 8:
		base = 1024
	default:
		base = 4096
	}
	if c.bits == 8 {
		return base / 64
	}
	return base
}

// waitTurnOn sleeps for the documented STANDBY -> ACTIVE turn-on time
// (2/ODR + 2 ms).
func (c *MMA8451QMinimal) waitTurnOn() {
	odr := c.odrHz
	if odr <= 0 {
		odr = 100
	}
	ms := float64(2000.0/odr + 2.0)
	time.Sleep(time.Duration(ms * float64(time.Millisecond)))
}

// Read returns 3-axis linear acceleration as (x, y, z) in *g*.
//
// Reads all six data bytes (0x01..0x06) in one burst so the X, Y, Z samples
// are guaranteed to come from a single measurement. In 8-bit fast-read mode
// only the three MSB registers are read.
func (c *MMA8451QMinimal) Read() (float32, float32, float32, error) {
	x, y, z, err := c.readCounts()
	if err != nil {
		return 0, 0, 0, err
	}
	scale := c.countsPerG()
	return float32(x) / scale, float32(y) / scale, float32(z) / scale, nil
}

// readCounts reads one sample and returns the signed acceleration counts of
// each axis at the current resolution.
func (c *MMA8451QMinimal) readCounts() (int32, int32, int32, error) {
	if c.bits == 8 {
		b, err := c.readBurst(mma8451qRegOutXMSB, 3)
		if err != nil {
			return 0, 0, 0, err
		}
		return int32(int8(b[0])), int32(int8(b[1])), int32(int8(b[2])), nil
	}
	b, err := c.readBurst(mma8451qRegOutXMSB, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	rx := int32(connection.ToSigned(uint32(b[0])<<8|uint32(b[1]), 16)) >> 2
	ry := int32(connection.ToSigned(uint32(b[2])<<8|uint32(b[3]), 16)) >> 2
	rz := int32(connection.ToSigned(uint32(b[4])<<8|uint32(b[5]), 16)) >> 2
	return rx, ry, rz, nil
}

// MMA8451QFull extends MMA8451QMinimal with the complete chip API:
// configurable range / data rate / oversampling, the 32-sample FIFO, the
// freefall / motion, transient, pulse and orientation detectors, Auto-SLEEP,
// the interrupt routing and the self-test.
//
// Uses struct embedding to promote every Minimal method onto Full
// automatically — no delegate methods to write by hand.
type MMA8451QFull struct {
	MMA8451QMinimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	lastStatus  uint8
}

// NewMMA8451QFull creates an MMA8451QFull; it runs the same initialization
// sequence as NewMMA8451QMinimal.
func NewMMA8451QFull(conn connection.RegisterConnection) (*MMA8451QFull, error) {
	m, err := NewMMA8451QMinimal(conn)
	if err != nil {
		return nil, err
	}
	return &MMA8451QFull{MMA8451QMinimal: *m}, nil
}

// writeStandby performs a STANDBY-only write: it reads CTRL_REG1, clears the
// ACTIVE bit, writes the target register, then restores ACTIVE and waits
// the turn-on time.
//
// When the target register is CTRL_REG1 itself, the new value is kept and
// only its ACTIVE bit is restored — the other fields are the ones the
// caller wanted to change.
func (c *MMA8451QFull) writeStandby(reg, val uint8) error {
	ctrl1, err := c.readReg8(mma8451qRegCtrlReg1)
	if err != nil {
		return err
	}
	wasActive := ctrl1&mma8451qCtrl1Active != 0
	if wasActive {
		if err := c.writeReg(mma8451qRegCtrlReg1, ctrl1&^mma8451qCtrl1Active); err != nil {
			return err
		}
	}
	if err := c.writeReg(reg, val); err != nil {
		return err
	}
	if wasActive {
		restore := ctrl1
		if reg == mma8451qRegCtrlReg1 {
			restore = val | mma8451qCtrl1Active
		}
		if err := c.writeReg(mma8451qRegCtrlReg1, restore); err != nil {
			return err
		}
		c.waitTurnOn()
	}
	return nil
}

// writeStandbyMulti performs several STANDBY-only writes in one STANDBY
// window.
func (c *MMA8451QFull) writeStandbyMulti(writes map[uint8]uint8) error {
	ctrl1, err := c.readReg8(mma8451qRegCtrlReg1)
	if err != nil {
		return err
	}
	wasActive := ctrl1&mma8451qCtrl1Active != 0
	if wasActive {
		if err := c.writeReg(mma8451qRegCtrlReg1, ctrl1&^mma8451qCtrl1Active); err != nil {
			return err
		}
	}
	for reg, val := range writes {
		if err := c.writeReg(reg, val); err != nil {
			return err
		}
	}
	if wasActive {
		if err := c.writeReg(mma8451qRegCtrlReg1, ctrl1); err != nil {
			return err
		}
		c.waitTurnOn()
	}
	return nil
}

// SetRange sets the full-scale measurement range: 2, 4 or 8 *g*.
//
// Returns an error when 8 *g* is requested while low-noise mode is enabled
// (the chip limits low-noise mode to ±4 *g*).
func (c *MMA8451QFull) SetRange(rangeG uint8) error {
	var code uint8
	switch rangeG {
	case 2:
		code = 0x00
	case 4:
		code = 0x01
	case 8:
		code = 0x02
	default:
		return fmt.Errorf("MMA8451Q range: %d g is not supported (use 2, 4 or 8)", rangeG)
	}
	if rangeG == 8 && c.lowNoise {
		return fmt.Errorf("MMA8451Q range: 8 g is not available in low-noise mode")
	}
	cur, err := c.readReg8(mma8451qRegXYZDataCfg)
	if err != nil {
		return err
	}
	if err := c.writeStandby(mma8451qRegXYZDataCfg, (cur&0xF0)|code); err != nil {
		return err
	}
	c.rangeG = rangeG
	return nil
}

// SetDataRate sets the output data rate in Hz. Supported rates are 800, 400,
// 200, 100, 50, 12.5, 6.25 and 1.56 Hz.
//
// Returns an error for any other rate.
func (c *MMA8451QFull) SetDataRate(rateHz float32) error {
	idx := -1
	for i, e := range mma8451qODRTable {
		if e.hz == rateHz {
			idx = i
			break
		}
	}
	if idx < 0 {
		return fmt.Errorf("MMA8451Q data rate: %v Hz is not supported", rateHz)
	}
	ctrl1, err := c.readReg8(mma8451qRegCtrlReg1)
	if err != nil {
		return err
	}
	out := (ctrl1 &^ mma8451qCtrl1DR) | mma8451qODRTable[idx].code
	if err := c.writeStandby(mma8451qRegCtrlReg1, out); err != nil {
		return err
	}
	c.odrHz = mma8451qODRTable[idx].hz
	c.odrIndex = idx
	return nil
}

// SetOversampling sets the oversampling mode used in WAKE / ACTIVE (mode:
// 0 normal, 1 low-noise low-power, 2 high resolution, 3 low power) and, when
// sleepMode is not nil, the mode used in SLEEP (0..3).
//
// Returns an error for a mode outside 0..3.
func (c *MMA8451QFull) SetOversampling(mode uint8, sleepMode *uint8) error {
	if mode > 3 {
		return fmt.Errorf("MMA8451Q oversampling mode: %d is not supported (use 0..3)", mode)
	}
	if sleepMode != nil && *sleepMode > 3 {
		return fmt.Errorf("MMA8451Q sleep oversampling mode: %d is not supported (use 0..3)", *sleepMode)
	}
	ctrl2, err := c.readReg8(mma8451qRegCtrlReg2)
	if err != nil {
		return err
	}
	out := (ctrl2 &^ mma8451qCtrl2MODS) | (mode & 0x03)
	if sleepMode != nil {
		out = (out &^ mma8451qCtrl2SMODS) | ((*sleepMode & 0x03) << 3)
	}
	if err := c.writeStandby(mma8451qRegCtrlReg2, out); err != nil {
		return err
	}
	c.oversample = mode & 0x03
	if sleepMode != nil {
		c.sleepMode = *sleepMode & 0x03
	}
	return nil
}

// SetLowNoise enables or disables the reduced-noise mode (CTRL_REG1.LNOISE).
//
// Returns an error when enabling it while the range is ±8 *g*: the chip
// limits low-noise mode to ±4 *g*.
func (c *MMA8451QFull) SetLowNoise(enabled bool) error {
	if enabled && c.rangeG == 8 {
		return fmt.Errorf("MMA8451Q low noise: not available at ±8 g")
	}
	ctrl1, err := c.readReg8(mma8451qRegCtrlReg1)
	if err != nil {
		return err
	}
	out := ctrl1
	if enabled {
		out |= mma8451qCtrl1LNoise
	} else {
		out &^= mma8451qCtrl1LNoise
	}
	if err := c.writeStandby(mma8451qRegCtrlReg1, out); err != nil {
		return err
	}
	c.lowNoise = enabled
	return nil
}

// SetResolution selects the output resolution: 14 (default, two bytes per
// axis) or 8 (fast read, MSB register only).
//
// Returns an error for any other resolution, and when the FIFO is enabled —
// F_READ may only be changed while F_MODE = 0.
func (c *MMA8451QFull) SetResolution(bits uint8) error {
	if bits != 14 && bits != 8 {
		return fmt.Errorf("MMA8451Q resolution: %d bits is not supported (use 14 or 8)", bits)
	}
	if c.fifoEnabled {
		return fmt.Errorf("MMA8451Q resolution: cannot be changed while the FIFO is enabled")
	}
	ctrl1, err := c.readReg8(mma8451qRegCtrlReg1)
	if err != nil {
		return err
	}
	out := ctrl1
	if bits == 8 {
		out |= mma8451qCtrl1FRead
	} else {
		out &^= mma8451qCtrl1FRead
	}
	if err := c.writeStandby(mma8451qRegCtrlReg1, out); err != nil {
		return err
	}
	c.bits = bits
	return nil
}

// Standby switches the device into STANDBY mode (CTRL_REG1.ACTIVE = 0).
func (c *MMA8451QFull) Standby() error {
	ctrl1, err := c.readReg8(mma8451qRegCtrlReg1)
	if err != nil {
		return err
	}
	return c.writeReg(mma8451qRegCtrlReg1, ctrl1&^mma8451qCtrl1Active)
}

// Activate switches the device into ACTIVE mode (CTRL_REG1.ACTIVE = 1) and
// waits the turn-on time (2/ODR + 2 ms).
func (c *MMA8451QFull) Activate() error {
	ctrl1, err := c.readReg8(mma8451qRegCtrlReg1)
	if err != nil {
		return err
	}
	if err := c.writeReg(mma8451qRegCtrlReg1, ctrl1|mma8451qCtrl1Active); err != nil {
		return err
	}
	c.waitTurnOn()
	return nil
}

// Reset performs a software reset (CTRL_REG2.RST). All registers return to
// their reset values and the device is left in STANDBY — re-run the
// initialization afterwards.
func (c *MMA8451QFull) Reset() error {
	if err := c.reset(); err != nil {
		return err
	}
	c.rangeG = 2
	c.odrHz = 100
	c.odrIndex = 3
	c.bits = 14
	c.oversample = 0
	c.sleepMode = 0
	c.lowNoise = false
	c.fifoMode = 0
	c.fifoEnabled = false
	return nil
}

// ReadRaw returns the signed acceleration counts of each axis at the current
// resolution (14-bit right-aligned, or 8-bit in fast-read mode).
func (c *MMA8451QFull) ReadRaw() (int32, int32, int32, error) {
	return c.readCounts()
}

// NewDataAvailable reports whether a new XYZ sample is available
// (STATUS.ZYXDR).
//
// The status is not valid while the FIFO is enabled — use FIFOLevel then.
func (c *MMA8451QFull) NewDataAvailable() (bool, error) {
	v, err := c.readReg8(mma8451qRegStatus)
	if err != nil {
		return false, err
	}
	return v&0x08 != 0, nil
}

// ReadStatus returns the raw STATUS register byte.
func (c *MMA8451QFull) ReadStatus() (uint8, error) {
	return c.readReg8(mma8451qRegStatus)
}

// ReadSystemMode returns the current system mode: MMA8451QModeStandby,
// MMA8451QModeWake or MMA8451QModeSleep (SYSMOD bits 1:0).
func (c *MMA8451QFull) ReadSystemMode() (uint8, error) {
	v, err := c.readReg8(mma8451qRegSysMod)
	if err != nil {
		return 0, err
	}
	return v & 0x03, nil
}

// SetHighPassOutput enables or disables the high-pass-filtered output
// (XYZ_DATA_CFG.HPF_OUT) and selects the high-pass cutoff (0..3).
//
// The cutoff in Hz depends on the output data rate and the oversampling
// mode — with normal oversampling at 100 Hz the four codes select 4, 2, 1
// and 0.5 Hz. The datasheet documents the full table (Table 23).
func (c *MMA8451QFull) SetHighPassOutput(enabled bool, cutoff uint8) error {
	if cutoff > 3 {
		return fmt.Errorf("MMA8451Q high-pass cutoff: %d is not supported (use 0..3)", cutoff)
	}
	cur, err := c.readReg8(mma8451qRegXYZDataCfg)
	if err != nil {
		return err
	}
	out := cur&0xFC | (cutoff & 0x03)
	if enabled {
		out |= 0x10
	} else {
		out &^= 0x10
	}
	if err := c.writeStandby(mma8451qRegXYZDataCfg, out); err != nil {
		return err
	}
	return nil
}

// SetOffset sets the per-axis zero-g offset correction in *g*.
//
// The registers have a resolution of 2 mg/LSB and a range of ±0.256 *g*;
// values outside that range are clamped.
func (c *MMA8451QFull) SetOffset(xG, yG, zG float32) error {
	ox := mma8451qEncodeOffset(xG)
	oy := mma8451qEncodeOffset(yG)
	oz := mma8451qEncodeOffset(zG)
	return c.writeStandbyMulti(map[uint8]uint8{
		mma8451qRegOffX: ox,
		mma8451qRegOffY: oy,
		mma8451qRegOffZ: oz,
	})
}

// GetOffset returns the per-axis zero-g offset correction in *g*.
func (c *MMA8451QFull) GetOffset() (float32, float32, float32, error) {
	b, err := c.readBurst(mma8451qRegOffX, 3)
	if err != nil {
		return 0, 0, 0, err
	}
	return float32(int8(b[0])) * mma8451qOffsetLSB,
		float32(int8(b[1])) * mma8451qOffsetLSB,
		float32(int8(b[2])) * mma8451qOffsetLSB, nil
}

// SetFIFO configures the 32-sample FIFO (F_SETUP).
//
// mode selects 0 disabled, 1 circular (oldest sample discarded), 2 fill
// (stops accepting when full) or 3 trigger. watermark is the number of
// samples at which the FIFO watermark flag is raised (0 disables it); in
// trigger mode it is the number of pre-trigger samples.
func (c *MMA8451QFull) SetFIFO(mode uint8, watermark uint8) error {
	if mode > 3 {
		return fmt.Errorf("MMA8451Q FIFO mode: %d is not supported (use 0..3)", mode)
	}
	if watermark > 31 {
		return fmt.Errorf("MMA8451Q FIFO watermark: %d is not supported (use 0..31)", watermark)
	}
	// The mode must go through 00 to switch between enabled modes.
	if c.fifoEnabled && mode != 0 {
		if err := c.writeReg(mma8451qRegFSetup, 0x00); err != nil {
			return err
		}
	}
	val := (mode&0x03)<<6 | (watermark & 0x3F)
	if err := c.writeReg(mma8451qRegFSetup, val); err != nil {
		return err
	}
	c.fifoMode = mode & 0x03
	c.fifoEnabled = c.fifoMode != 0
	return nil
}

// SetFIFOTrigger selects the events that trigger a FIFO capture
// (TRIG_CFG): transient, orientation, pulse and/or freefall / motion.
//
// At least one source must be selected.
func (c *MMA8451QFull) SetFIFOTrigger(transient, orientation, pulse, motion bool) error {
	var val uint8
	if transient {
		val |= 0x20
	}
	if orientation {
		val |= 0x10
	}
	if pulse {
		val |= 0x08
	}
	if motion {
		val |= 0x04
	}
	if val == 0 {
		return fmt.Errorf("MMA8451Q FIFO trigger: at least one source must be selected")
	}
	return c.writeStandby(mma8451qRegTrigCfg, val)
}

// FIFOLevel returns the number of samples currently in the FIFO (F_CNT,
// 0..32).
func (c *MMA8451QFull) FIFOLevel() (uint8, error) {
	v, err := c.readReg8(mma8451qRegStatus)
	if err != nil {
		return 0, err
	}
	return v & 0x3F, nil
}

// ReadFIFOStatus decodes the FIFO status register and returns
// (overflow, watermark, count).
//
// Reading the status register clears the FIFO interrupt source.
func (c *MMA8451QFull) ReadFIFOStatus() (bool, bool, uint8, error) {
	v, err := c.readReg8(mma8451qRegStatus)
	if err != nil {
		return false, false, 0, err
	}
	return v&0x80 != 0, v&0x40 != 0, v & 0x3F, nil
}

// ReadFIFO drains the FIFO and returns the samples as (x, y, z) triples in
// *g*.
//
// count limits the number of samples read; a negative value (or 0) reads
// every sample currently in the FIFO. Each sample is read with its own
// transaction starting at 0x01.
func (c *MMA8451QFull) ReadFIFO(count int) ([][]float32, error) {
	out := make([][]float32, 0, 8)
	for {
		if count >= 0 && len(out) >= count {
			break
		}
		x, y, z, err := c.readCounts()
		if err != nil {
			return out, err
		}
		scale := c.countsPerG()
		out = append(out, []float32{float32(x) / scale, float32(y) / scale, float32(z) / scale})
		// Stop when the FIFO runs dry.
		level, err := c.FIFOLevel()
		if err != nil {
			return out, err
		}
		if level == 0 {
			break
		}
	}
	return out, nil
}

// SetFreefallMotion configures the freefall / motion detector.
//
// motion selects motion detection (any enabled axis above the threshold)
// instead of freefall detection (all enabled axes below it). axes is a bit
// mask with X = 1, Y = 2 and Z = 4. thresholdG is the trigger level in *g*
// (0.063 g/LSB). debounceMs is the debounce time, latch freezes the status
// flags until the source register is read, and debounceClear clears the
// debounce counter when the condition is lost.
func (c *MMA8451QFull) SetFreefallMotion(motion bool, axes uint8, thresholdG float32, debounceMs float32, latch bool, debounceClear bool) error {
	if axes&0x07 == 0 {
		return fmt.Errorf("MMA8451Q freefall/motion: at least one axis must be enabled")
	}
	if thresholdG < 0 {
		return fmt.Errorf("MMA8451Q freefall/motion: threshold must not be negative")
	}
	cfg, err := c.readReg8(mma8451qRegFFMTCfg)
	if err != nil {
		return err
	}
	out := cfg & 0xC7
	out |= (axes & 0x07) << 3
	if motion {
		out |= 0x40
	}
	if latch {
		out |= 0x80
	}
	if err := c.writeStandby(mma8451qRegFFMTCfg, out); err != nil {
		return err
	}
	ths := mma8451qEncodeThreshold(thresholdG)
	var thsVal uint8 = ths & 0x7F
	if debounceClear {
		thsVal |= 0x80
	}
	if err := c.writeReg(mma8451qRegFFMTThs, thsVal); err != nil {
		return err
	}
	count := c.debounceCount(debounceMs)
	return c.writeReg(mma8451qRegFFMTCount, count)
}

// ReadMotionSource returns the raw FF_MT_SRC byte.
//
// With the detector latched (ELE = 1) the flags are cleared by this read.
func (c *MMA8451QFull) ReadMotionSource() (uint8, error) {
	return c.readReg8(mma8451qRegFFMTSrc)
}

// SetTransient configures the transient (high-pass filtered motion)
// detector.
//
// axes is a bit mask with X = 1, Y = 2 and Z = 4. thresholdG is the trigger
// level in *g* (0.063 g/LSB). debounceMs is the debounce time, bypassHPF
// makes the detector behave like motion detection, and latch freezes the
// status flags until the source register is read.
func (c *MMA8451QFull) SetTransient(axes uint8, thresholdG float32, debounceMs float32, bypassHPF bool, latch bool) error {
	if axes&0x07 == 0 {
		return fmt.Errorf("MMA8451Q transient: at least one axis must be enabled")
	}
	if thresholdG < 0 {
		return fmt.Errorf("MMA8451Q transient: threshold must not be negative")
	}
	cfg, err := c.readReg8(mma8451qRegTransientC)
	if err != nil {
		return err
	}
	out := cfg & 0xE0
	out |= (axes & 0x07) << 1
	if latch {
		out |= 0x10
	}
	if bypassHPF {
		out |= 0x01
	}
	if err := c.writeStandby(mma8451qRegTransientC, out); err != nil {
		return err
	}
	ths := mma8451qEncodeThreshold(thresholdG)
	if err := c.writeReg(mma8451qRegTransientT, ths&0x7F); err != nil {
		return err
	}
	count := c.debounceCount(debounceMs)
	return c.writeReg(mma8451qRegTransientC2, count)
}

// ReadTransientSource returns the raw TRANSIENT_SRC byte. The read clears
// the flags and the transient interrupt.
func (c *MMA8451QFull) ReadTransientSource() (uint8, error) {
	return c.readReg8(mma8451qRegTransientS)
}

// SetPulse configures the tap (pulse) detector.
//
// single and double are axis bit masks with X = 1, Y = 2 and Z = 4.
// thresholdG holds the per-axis thresholds in *g* (0.063 g/LSB).
// timeLimitMs, latencyMs and windowMs are the pulse timers. latch freezes the
// status flags until the source register is read, doubleAbort suspends
// double-tap detection when a pulse starts inside the latency window, and lpf
// / hpf select the pulse low-pass and high-pass filters.
func (c *MMA8451QFull) SetPulse(single uint8, double uint8, thresholdG [3]float32, timeLimitMs float32, latencyMs float32, windowMs float32, latch bool, doubleAbort bool, lpf bool, hpf bool) error {
	if single&0x07 == 0 && double&0x07 == 0 {
		return fmt.Errorf("MMA8451Q pulse: at least one single or double tap axis must be enabled")
	}
	for _, t := range thresholdG {
		if t < 0 {
			return fmt.Errorf("MMA8451Q pulse: threshold must not be negative")
		}
	}
	cfg, err := c.readReg8(mma8451qRegPulseCfg)
	if err != nil {
		return err
	}
	out := cfg & 0xC0
	out |= (single & 0x07) & 0x07
	out |= (double & 0x07) << 3
	if latch {
		out |= 0x40
	}
	if doubleAbort {
		out |= 0x80
	}
	if err := c.writeStandby(mma8451qRegPulseCfg, out); err != nil {
		return err
	}
	for i, reg := range []uint8{mma8451qRegPulseThsX, mma8451qRegPulseThsY, mma8451qRegPulseThsZ} {
		if err := c.writeReg(reg, mma8451qEncodeThreshold(thresholdG[i])&0x7F); err != nil {
			return err
		}
	}
	mode := c.oversample
	if mode > 3 {
		mode = 0
	}
	idx := c.odrIndex
	if idx < 0 || idx > 7 {
		idx = 3
	}
	tmltStep := mma8451qPulseTMLTStep[idx][mode]
	ltcyStep := mma8451qPulseLTCYStep[idx][mode]
	if !lpf {
		tmltStep = mma8451qPulseTMLTStepNoLPF[idx][mode]
		ltcyStep = mma8451qPulseLTCYStepNoLPF[idx][mode]
	}
	if err := c.writeStandby(mma8451qRegPulseTMLT, mma8451qClamp255(timeLimitMs/tmltStep)); err != nil {
		return err
	}
	if err := c.writeStandby(mma8451qRegPulseLTCY, mma8451qClamp255(latencyMs/ltcyStep)); err != nil {
		return err
	}
	if err := c.writeStandby(mma8451qRegPulseWIND, mma8451qClamp255(windowMs/ltcyStep)); err != nil {
		return err
	}
	// The Pulse_HPF_BYP / Pulse_LPF_EN bits live in HP_FILTER_CUTOFF.
	hpfCut, err := c.readReg8(mma8451qRegHPFilterCut)
	if err != nil {
		return err
	}
	hpfOut := hpfCut & 0x03
	if hpf {
		hpfOut &^= 0x20
	} else {
		hpfOut |= 0x20
	}
	if lpf {
		hpfOut |= 0x10
	} else {
		hpfOut &^= 0x10
	}
	return c.writeStandby(mma8451qRegHPFilterCut, hpfOut)
}

// ReadPulseSource returns the raw PULSE_SRC byte. The read clears the flags
// and the pulse interrupt.
func (c *MMA8451QFull) ReadPulseSource() (uint8, error) {
	return c.readReg8(mma8451qRegPulseSrc)
}

// SetOrientation configures the portrait / landscape and back / front
// detector.
//
// thresholdDeg must be one of the discrete trip angles 15, 20, 30, 35, 40,
// 45, 55, 60, 70 or 75 degrees. hysteresis is the HYS code (0..7), zLock is
// the ZLOCK code (0..7) and backFront is the BKFR code (0..3).
//
// Returns an error when the trip angle is not a supported value, or when
// THS + HYS falls outside 1..31 — the datasheet requires that sum to hold
// for the detector to work.
func (c *MMA8451QFull) SetOrientation(enabled bool, debounceMs float32, thresholdDeg int, hysteresis uint8, zLock uint8, backFront uint8) error {
	if hysteresis > 7 {
		return fmt.Errorf("MMA8451Q orientation hysteresis: %d is not supported (use 0..7)", hysteresis)
	}
	if zLock > 7 {
		return fmt.Errorf("MMA8451Q orientation Z-lock: %d is not supported (use 0..7)", zLock)
	}
	if backFront > 3 {
		return fmt.Errorf("MMA8451Q orientation back/front: %d is not supported (use 0..3)", backFront)
	}
	ths, ok := mma8451qOrientationThresholds[thresholdDeg]
	if !ok {
		return fmt.Errorf("MMA8451Q orientation threshold: %d degrees is not supported", thresholdDeg)
	}
	thsReg := ths << 3
	if int(ths)+int(hysteresis) < 1 || int(ths)+int(hysteresis) > 31 {
		return fmt.Errorf("MMA8451Q orientation: THS + HYS must be in 1..31")
	}
	thsReg |= hysteresis & 0x07
	zcomp := (backFront&0x03)<<6 | (zLock & 0x07)
	writes := map[uint8]uint8{
		mma8451qRegPLThsReg:  thsReg,
		mma8451qRegPLBFZComp: zcomp,
	}
	if err := c.writeStandbyMulti(writes); err != nil {
		return err
	}
	plCfg, err := c.readReg8(mma8451qRegPLCfg)
	if err != nil {
		return err
	}
	out := plCfg & 0x7F
	if enabled {
		out |= 0x40
	}
	if err := c.writeStandby(mma8451qRegPLCfg, out); err != nil {
		return err
	}
	count := c.debounceCount(debounceMs)
	return c.writeReg(mma8451qRegPLCount, count)
}

// ReadOrientation decodes the PL_STATUS register and returns
// (changed, lockout, portraitLandscape, back).
//
// changed is the NEWLP flag (a new orientation was detected), lockout is the
// Z-tilt lockout flag, portraitLandscape is 0 portrait up, 1 portrait down,
// 2 landscape right and 3 landscape left, and back reports the back/front
// state. The read clears NEWLP.
func (c *MMA8451QFull) ReadOrientation() (bool, bool, uint8, bool, error) {
	v, err := c.readReg8(mma8451qRegPLStatus)
	if err != nil {
		return false, false, 0, false, err
	}
	changed := v&0x80 != 0
	lockout := v&0x40 != 0
	lapo := (v >> 1) & 0x03
	back := v&0x01 != 0
	return changed, lockout, lapo, back, nil
}

// SetAutoSleep configures the Auto-SLEEP / Auto-WAKE behaviour.
//
// enabled turns Auto-SLEEP on. sleepRateHz selects the sleep-mode output
// data rate (50, 12.5, 6.25 or 1.56 Hz). timeoutS is the inactivity timeout
// in seconds. wakeSources is a mask of the sources that can wake the device
// — only MMA8451QSourceTransient, MMA8451QSourceOrientation,
// MMA8451QSourcePulse and MMA8451QSourceFFMT are valid.
func (c *MMA8451QFull) SetAutoSleep(enabled bool, sleepRateHz float32, timeoutS float32, wakeSources uint8) error {
	var aslpRate uint8
	switch sleepRateHz {
	case 50:
		aslpRate = 0x00
	case 12.5:
		aslpRate = 0x01
	case 6.25:
		aslpRate = 0x02
	case 1.56:
		aslpRate = 0x03
	default:
		return fmt.Errorf("MMA8451Q auto-sleep rate: %v Hz is not supported (use 50, 12.5, 6.25 or 1.56)", sleepRateHz)
	}
	valid := MMA8451QSourceTransient | MMA8451QSourceOrientation | MMA8451QSourcePulse | MMA8451QSourceFFMT
	if wakeSources&^valid != 0 {
		return fmt.Errorf("MMA8451Q auto-sleep wake sources: 0x%02X contains an unsupported source", wakeSources)
	}
	ctrl2, err := c.readReg8(mma8451qRegCtrlReg2)
	if err != nil {
		return err
	}
	out := ctrl2 &^ mma8451qCtrl2SLPE
	if enabled {
		out |= mma8451qCtrl2SLPE
	}
	if err := c.writeStandby(mma8451qRegCtrlReg2, out); err != nil {
		return err
	}
	ctrl1, err := c.readReg8(mma8451qRegCtrlReg1)
	if err != nil {
		return err
	}
	ctrl1 = (ctrl1 &^ mma8451qCtrl1AslpRate) | (aslpRate << 6)
	if err := c.writeStandby(mma8451qRegCtrlReg1, ctrl1); err != nil {
		return err
	}
	stepS := float32(0.32)
	if c.odrHz <= 1.56 {
		stepS = 0.64
	}
	count := mma8451qClamp255(timeoutS / stepS)
	if err := c.writeStandby(mma8451qRegASLPCount, count); err != nil {
		return err
	}
	ctrl3, err := c.readReg8(mma8451qRegCtrlReg3)
	if err != nil {
		return err
	}
	out3 := ctrl3 & 0x07
	if wakeSources&MMA8451QSourceTransient != 0 {
		out3 |= mma8451qCtrl3WakeTrans
	}
	if wakeSources&MMA8451QSourceOrientation != 0 {
		out3 |= mma8451qCtrl3WakeLndPrt
	}
	if wakeSources&MMA8451QSourcePulse != 0 {
		out3 |= mma8451qCtrl3WakePulse
	}
	if wakeSources&MMA8451QSourceFFMT != 0 {
		out3 |= mma8451qCtrl3WakeFFMT
	}
	return c.writeStandby(mma8451qRegCtrlReg3, out3)
}

// SetFIFOGate enables or disables the FIFO gate (CTRL_REG3.FIFO_GATE). With
// the gate enabled, new FIFO input is blocked around WAKE/SLEEP transitions
// until the FIFO has been emptied.
func (c *MMA8451QFull) SetFIFOGate(enabled bool) error {
	ctrl3, err := c.readReg8(mma8451qRegCtrlReg3)
	if err != nil {
		return err
	}
	out := ctrl3
	if enabled {
		out |= mma8451qCtrl3FifoGate
	} else {
		out &^= mma8451qCtrl3FifoGate
	}
	return c.writeStandby(mma8451qRegCtrlReg3, out)
}

// SetInterruptPins configures the polarity and drive of the INT1 / INT2
// outputs (CTRL_REG3.IPOL and PP_OD). Both pins share the configuration.
//
// The chip's default is active-low, push-pull.
func (c *MMA8451QFull) SetInterruptPins(activeHigh bool, openDrain bool) error {
	ctrl3, err := c.readReg8(mma8451qRegCtrlReg3)
	if err != nil {
		return err
	}
	out := ctrl3 & 0xFC
	if activeHigh {
		out |= mma8451qCtrl3IPol
	}
	if openDrain {
		out |= mma8451qCtrl3PPOD
	}
	return c.writeStandby(mma8451qRegCtrlReg3, out)
}

// EnableInterrupt enables one interrupt source (CTRL_REG4.INT_EN_*) and
// routes it to INT1 (pin 1) or INT2 (pin 2) via CTRL_REG5.INT_CFG_*.
//
// The source constants are MMA8451QSourceDataReady, MMA8451QSourceFFMT,
// MMA8451QSourcePulse, MMA8451QSourceOrientation, MMA8451QSourceTransient,
// MMA8451QSourceFIFO and MMA8451QSourceAutoSleep.
//
// Enabling a source only routes it — the detector behind it is configured
// with the Set* methods.
func (c *MMA8451QFull) EnableInterrupt(source uint8, pin uint8) error {
	if source == 0 {
		return fmt.Errorf("MMA8451Q interrupt: no source given")
	}
	if pin != 1 && pin != 2 {
		return fmt.Errorf("MMA8451Q interrupt pin: %d is not supported (use 1 or 2)", pin)
	}
	ctrl4, err := c.readReg8(mma8451qRegCtrlReg4)
	if err != nil {
		return err
	}
	if err := c.writeStandby(mma8451qRegCtrlReg4, ctrl4|source); err != nil {
		return err
	}
	ctrl5, err := c.readReg8(mma8451qRegCtrlReg5)
	if err != nil {
		return err
	}
	out := ctrl5
	if pin == 1 {
		out |= source
	} else {
		out &^= source
	}
	return c.writeStandby(mma8451qRegCtrlReg5, out)
}

// DisableInterrupt disables one interrupt source (clears the matching
// CTRL_REG4.INT_EN_* bit).
func (c *MMA8451QFull) DisableInterrupt(source uint8) error {
	if source == 0 {
		return fmt.Errorf("MMA8451Q interrupt: no source given")
	}
	ctrl4, err := c.readReg8(mma8451qRegCtrlReg4)
	if err != nil {
		return err
	}
	return c.writeStandby(mma8451qRegCtrlReg4, ctrl4&^source)
}

// PollInterrupt reads INT_SOURCE and returns it.
//
// For every set bit except MMA8451QSourceDataReady the driver also reads the
// matching source register, so the flag (and the INT pin) is cleared.
// MMA8451QSourceDataReady stays set — clear it by reading the data.
func (c *MMA8451QFull) PollInterrupt() (uint8, error) {
	v, err := c.readReg8(mma8451qRegIntSource)
	if err != nil {
		return 0, err
	}
	if v&MMA8451QSourceFIFO != 0 {
		if _, err := c.readReg8(mma8451qRegStatus); err != nil {
			return v, err
		}
	}
	if v&MMA8451QSourceFFMT != 0 {
		if _, err := c.readReg8(mma8451qRegFFMTSrc); err != nil {
			return v, err
		}
	}
	if v&MMA8451QSourcePulse != 0 {
		if _, err := c.readReg8(mma8451qRegPulseSrc); err != nil {
			return v, err
		}
	}
	if v&MMA8451QSourceOrientation != 0 {
		if _, err := c.readReg8(mma8451qRegPLStatus); err != nil {
			return v, err
		}
	}
	if v&MMA8451QSourceTransient != 0 {
		if _, err := c.readReg8(mma8451qRegTransientS); err != nil {
			return v, err
		}
	}
	if v&MMA8451QSourceAutoSleep != 0 {
		if _, err := c.readReg8(mma8451qRegSysMod); err != nil {
			return v, err
		}
	}
	return v, nil
}

// OnInterrupt subscribes callback to interrupt events; it receives the
// INT_SOURCE mask as returned by PollInterrupt.
//
// With the connection's IntPin wired, it fires on every interrupt edge —
// falling for active-low, rising for active-high, following the configured
// IPOL (call SetInterruptPins first). Otherwise a 5 ms polling goroutine
// calls back whenever the status mask changes.
func (c *MMA8451QFull) OnInterrupt(callback func(status uint8)) error {
	c.mu.Lock()
	if c.unsubscribe != nil {
		c.unsubscribe()
		c.unsubscribe = nil
	}
	if c.pollPin != nil {
		_ = c.pollPin.Close()
		c.pollPin = nil
	}
	c.callback = callback
	c.mu.Unlock()

	trigger := connection.Falling
	pin := c.conn.IntPin()
	if pin == nil {
		status, err := c.PollInterrupt()
		if err != nil {
			return err
		}
		poll := connection.NewDefaultPollingInputPin()
		c.mu.Lock()
		c.lastStatus = status
		c.pollPin = poll
		c.mu.Unlock()
		pin = poll
	} else {
		ctrl3, err := c.readReg8(mma8451qRegCtrlReg3)
		if err != nil {
			return err
		}
		if ctrl3&mma8451qCtrl3IPol != 0 {
			trigger = connection.Rising
		}
	}
	unsub := pin.OnEdge(trigger, func() { c.handleEdge() })
	c.mu.Lock()
	c.unsubscribe = unsub
	c.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (c *MMA8451QFull) OffInterrupt() error {
	c.mu.Lock()
	unsub := c.unsubscribe
	c.unsubscribe = nil
	c.callback = nil
	poll := c.pollPin
	c.pollPin = nil
	c.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (c *MMA8451QFull) handleEdge() {
	status, err := c.PollInterrupt()
	if err != nil {
		return
	}
	c.mu.Lock()
	cb := c.callback
	// A real interrupt edge is always an event; the polling fallback ticks
	// continuously, so it only reports changes of the status mask.
	fire := c.pollPin == nil || status != c.lastStatus
	c.lastStatus = status
	c.mu.Unlock()
	if fire && cb != nil {
		cb(status)
	}
}

// SelfTest runs the electrostatic self-test and reports (ok, dx, dy, dz)
// where dx, dy and dz are the measured count deltas in 14-bit counts.
//
// The device is put into STANDBY, configured for ±4 *g* at 100 Hz with
// normal oversampling and ACTIVE, a few samples are averaged, the self-test
// force is applied, more samples are averaged, and the previous configuration
// is restored.
//
// The datasheet lists only typical deltas (X +181, Y +255, Z +1680 counts)
// and no limits, so ok is true when every axis moved by more than half its
// typical value — a heuristic that has to be verified on hardware.
func (c *MMA8451QFull) SelfTest() (bool, int32, int32, int32, error) {
	// Save the current configuration.
	ctrl1, err := c.readReg8(mma8451qRegCtrlReg1)
	if err != nil {
		return false, 0, 0, 0, err
	}
	ctrl2, err := c.readReg8(mma8451qRegCtrlReg2)
	if err != nil {
		return false, 0, 0, 0, err
	}
	xyzCfg, err := c.readReg8(mma8451qRegXYZDataCfg)
	if err != nil {
		return false, 0, 0, 0, err
	}
	restore := func() {
		_ = c.writeReg(mma8451qRegCtrlReg1, ctrl1&^mma8451qCtrl1Active)
		_ = c.writeReg(mma8451qRegXYZDataCfg, xyzCfg)
		_ = c.writeReg(mma8451qRegCtrlReg2, ctrl2&^mma8451qCtrl2ST)
		_ = c.writeReg(mma8451qRegCtrlReg1, ctrl1)
	}

	// STANDBY, ±4 g, 100 Hz, normal oversampling.
	testCtrl1 := uint8(mma8451qCtrl1Active) | 0x18 // DR = 011 (100 Hz)
	if err := c.writeReg(mma8451qRegCtrlReg1, testCtrl1&^mma8451qCtrl1Active); err != nil {
		return false, 0, 0, 0, err
	}
	if err := c.writeReg(mma8451qRegXYZDataCfg, 0x01); err != nil {
		return false, 0, 0, 0, err
	}
	if err := c.writeReg(mma8451qRegCtrlReg2, ctrl2&^mma8451qCtrl2ST&^mma8451qCtrl2MODS); err != nil {
		return false, 0, 0, 0, err
	}
	if err := c.writeReg(mma8451qRegCtrlReg1, testCtrl1); err != nil {
		return false, 0, 0, 0, err
	}
	c.waitTurnOn()

	before, err := c.averageCounts(4)
	if err != nil {
		restore()
		return false, 0, 0, 0, err
	}

	// Apply the self-test force.
	if err := c.writeReg(mma8451qRegCtrlReg2, ctrl2&^mma8451qCtrl2MODS|mma8451qCtrl2ST); err != nil {
		restore()
		return false, 0, 0, 0, err
	}
	time.Sleep(20 * time.Millisecond)

	after, err := c.averageCounts(4)
	if err != nil {
		restore()
		return false, 0, 0, 0, err
	}

	// Clear the self-test bit and restore the previous configuration.
	restore()

	dx := after[0] - before[0]
	dy := after[1] - before[1]
	dz := after[2] - before[2]
	ok := dx > mma8451qSelfTestTypical[0]/2 &&
		dy > mma8451qSelfTestTypical[1]/2 &&
		dz > mma8451qSelfTestTypical[2]/2
	return ok, dx, dy, dz, nil
}

// averageCounts reads n samples and returns the per-axis mean counts.
func (c *MMA8451QFull) averageCounts(n int) ([3]int32, error) {
	var sum [3]int32
	for i := 0; i < n; i++ {
		x, y, z, err := c.readCounts()
		if err != nil {
			return sum, err
		}
		sum[0] += x
		sum[1] += y
		sum[2] += z
		time.Sleep(10 * time.Millisecond)
	}
	return [3]int32{sum[0] / int32(n), sum[1] / int32(n), sum[2] / int32(n)}, nil
}

// debounceCount converts a debounce time in milliseconds into the register
// count for the current output data rate and oversampling mode.
func (c *MMA8451QFull) debounceCount(debounceMs float32) uint8 {
	idx := c.odrIndex
	if idx < 0 || idx > 7 {
		idx = 3
	}
	mode := c.oversample
	if mode > 3 {
		mode = 0
	}
	step := mma8451qODRTable[idx].debStep[mode]
	if step <= 0 {
		return 0
	}
	return mma8451qClamp255(debounceMs / step)
}

// mma8451qEncodeThreshold converts a threshold in *g* into the 7-bit
// register code (0.063 g/LSB), clamped to 0..127.
func mma8451qEncodeThreshold(thresholdG float32) uint8 {
	code := int32(math.Round(float64(thresholdG / MMA8451QThresholdLSB)))
	if code < 0 {
		code = 0
	}
	if code > 127 {
		code = 127
	}
	return uint8(code)
}

// mma8451qEncodeOffset converts an offset in *g* into the signed 8-bit
// register value (2 mg/LSB), clamped to -128..127.
func mma8451qEncodeOffset(offsetG float32) uint8 {
	code := int32(math.Round(float64(offsetG / mma8451qOffsetLSB)))
	if code > 127 {
		code = 127
	}
	if code < -128 {
		code = -128
	}
	return uint8(int8(code))
}

// mma8451qClamp255 rounds a float to the nearest integer and clamps it to
// 0..255.
func mma8451qClamp255(v float32) uint8 {
	code := int32(math.Round(float64(v)))
	if code < 0 {
		code = 0
	}
	if code > 255 {
		code = 255
	}
	return uint8(code)
}

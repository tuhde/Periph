package accelerometer

import (
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// FXOS8700 registers.
const (
	fxos8700RegStatus       uint8 = 0x00
	fxos8700RegOutXMsb      uint8 = 0x01
	fxos8700RegFSetup       uint8 = 0x09
	fxos8700RegTrigCfg      uint8 = 0x0A
	fxos8700RegSysmod       uint8 = 0x0B
	fxos8700RegIntSource    uint8 = 0x0C
	fxos8700RegWhoAmI       uint8 = 0x0D
	fxos8700RegXyzDataCfg   uint8 = 0x0E
	fxos8700RegHpFilterCut  uint8 = 0x0F
	fxos8700RegPlStatus     uint8 = 0x10
	fxos8700RegPlCfg        uint8 = 0x11
	fxos8700RegPlCount      uint8 = 0x12
	fxos8700RegPlBfZcomp    uint8 = 0x13
	fxos8700RegPlThs        uint8 = 0x14
	fxos8700RegAFfmtCfg     uint8 = 0x15
	fxos8700RegAFfmtSrc     uint8 = 0x16
	fxos8700RegAFfmtThs     uint8 = 0x17
	fxos8700RegAFfmtCount   uint8 = 0x18
	fxos8700RegTransientCfg uint8 = 0x1D
	fxos8700RegTransientSrc uint8 = 0x1E
	fxos8700RegTransientThs uint8 = 0x1F
	fxos8700RegTransientCnt uint8 = 0x20
	fxos8700RegPulseCfg     uint8 = 0x21
	fxos8700RegPulseSrc     uint8 = 0x22
	fxos8700RegPulseThsx    uint8 = 0x23
	fxos8700RegPulseTmlt    uint8 = 0x26
	fxos8700RegPulseLtcy    uint8 = 0x27
	fxos8700RegPulseWind    uint8 = 0x28
	fxos8700RegAslpCount    uint8 = 0x29
	fxos8700RegCtrl1        uint8 = 0x2A
	fxos8700RegCtrl2        uint8 = 0x2B
	fxos8700RegCtrl3        uint8 = 0x2C
	fxos8700RegCtrl4        uint8 = 0x2D
	fxos8700RegCtrl5        uint8 = 0x2E
	fxos8700RegOffX         uint8 = 0x2F
	fxos8700RegMDrStatus    uint8 = 0x32
	fxos8700RegMOutXMsb     uint8 = 0x33
	fxos8700RegMOffXMsb     uint8 = 0x3F
	fxos8700RegMaxXMsb      uint8 = 0x45
	fxos8700RegMinXMsb      uint8 = 0x4B
	fxos8700RegTemp         uint8 = 0x51
	fxos8700RegMCtrl1       uint8 = 0x5B
	fxos8700RegMCtrl2       uint8 = 0x5C
	fxos8700RegMCtrl3       uint8 = 0x5D
)

// FXOS8700 bit masks.
const (
	fxos8700Ctrl1Active     uint8 = 0x01
	fxos8700Ctrl1FRead      uint8 = 0x02
	fxos8700Ctrl1Lnoise     uint8 = 0x04
	fxos8700Ctrl1DrMask     uint8 = 0x38
	fxos8700Ctrl1AslpMask   uint8 = 0xC0
	fxos8700Ctrl2St         uint8 = 0x80
	fxos8700Ctrl2Rst        uint8 = 0x40
	fxos8700Ctrl2SmodsMask  uint8 = 0x18
	fxos8700Ctrl2Slpe       uint8 = 0x04
	fxos8700Ctrl2ModsMask   uint8 = 0x03
	fxos8700StatusZyxdr     uint8 = 0x08
	fxos8700MCtrl1Acal      uint8 = 0x80
	fxos8700MCtrl1Rst       uint8 = 0x40
	fxos8700MCtrl1Ost       uint8 = 0x20
	fxos8700MCtrl1OsMask    uint8 = 0x1C
	fxos8700MCtrl1HmsMask   uint8 = 0x03
	fxos8700MCtrl2HybAutoin uint8 = 0x20
)

const (
	// FXOS8700I2CAddress is the I²C address for SA1 = 0, SA0 = 0 (default);
	// 0x1C-0x1F are selectable.
	FXOS8700I2CAddress uint8 = 0x1E
)

// Interrupt source bits — the values of PollInterrupt's return mask (the
// INT_SOURCE / CTRL_REG4 / CTRL_REG5 bit positions).
const (
	// FXOS8700SourceDataReady fires on a new sample (accelerometer in
	// accel-only mode, magnetometer otherwise).
	FXOS8700SourceDataReady uint8 = 0x01
	// FXOS8700SourceAccelVecm fires on an accelerometer vector-magnitude change.
	FXOS8700SourceAccelVecm uint8 = 0x02
	// FXOS8700SourceFfMt fires on freefall / motion.
	FXOS8700SourceFfMt uint8 = 0x04
	// FXOS8700SourcePulse fires on a single / double tap.
	FXOS8700SourcePulse uint8 = 0x08
	// FXOS8700SourceOrientation fires on an orientation change.
	FXOS8700SourceOrientation uint8 = 0x10
	// FXOS8700SourceTransient fires on a transient.
	FXOS8700SourceTransient uint8 = 0x20
	// FXOS8700SourceFifo fires on a FIFO overflow or watermark.
	FXOS8700SourceFifo uint8 = 0x40
	// FXOS8700SourceAutoSleep fires on a WAKE <-> SLEEP transition.
	FXOS8700SourceAutoSleep uint8 = 0x80
)

// Operating modes.
const (
	// FXOS8700ModeAccelOnly runs only the accelerometer.
	FXOS8700ModeAccelOnly uint8 = 0
	// FXOS8700ModeMagOnly runs only the magnetometer.
	FXOS8700ModeMagOnly uint8 = 1
	// FXOS8700ModeHybrid runs both sensors at half the ODR each.
	FXOS8700ModeHybrid uint8 = 2
)

// FIFO modes.
const (
	// FXOS8700FifoDisabled disables the FIFO.
	FXOS8700FifoDisabled uint8 = 0
	// FXOS8700FifoCircular discards the oldest sample when full.
	FXOS8700FifoCircular uint8 = 1
	// FXOS8700FifoFill stops accepting samples when full.
	FXOS8700FifoFill uint8 = 2
	// FXOS8700FifoTrigger runs the trigger mode.
	FXOS8700FifoTrigger uint8 = 3
)

// fxos8700OdrHz are the system output data rates selected by CTRL_REG1.dr.
var fxos8700OdrHz = [8]float32{800, 400, 200, 100, 50, 12.5, 6.25, 1.5625}

// fxos8700StepMs holds the debounce time steps in ms (also PULSE_TMLT with
// the pulse LPF on), indexed [mods][row]; row is the dr code, shifted by one
// in hybrid mode.
var fxos8700StepMs = [4][8]float32{
	{1.25, 2.5, 5, 10, 20, 20, 20, 20},
	{1.25, 2.5, 5, 10, 20, 80, 80, 80},
	{1.25, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5},
	{1.25, 2.5, 5, 10, 20, 80, 160, 160},
}

// fxos8700PulseStepNoLpfMs holds the PULSE_TMLT steps with the pulse low-pass
// filter off (PULSE_LTCY / PULSE_WIND steps are twice as large).
var fxos8700PulseStepNoLpfMs = [4][8]float32{
	{0.625, 0.625, 1.25, 2.5, 5, 5, 5, 5},
	{0.625, 0.625, 1.25, 2.5, 5, 20, 20, 20},
	{0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625},
	{0.625, 1.25, 2.5, 5, 10, 40, 40, 40},
}

// fxos8700PlThresholds maps an orientation trip angle in degrees to P_L_THS<4:0>.
var fxos8700PlThresholds = map[uint8]uint8{
	15: 0x07, 20: 0x09, 30: 0x0C, 35: 0x0D, 40: 0x0F,
	45: 0x10, 55: 0x13, 60: 0x14, 70: 0x17, 75: 0x19,
}

// fxos8700AslpRates maps an Auto-SLEEP rate in Hz to CTRL_REG1.aslp_rate.
var fxos8700AslpRates = []struct {
	hz   float32
	code uint8
}{{50, 0}, {12.5, 1}, {6.25, 2}, {1.56, 3}}

// FXOS8700Minimal is the minimal FXOS8700CQ interface — acceleration and
// magnetic field.
//
// The FXOS8700CQ is a 6-axis e-compass: a 14-bit three-axis accelerometer
// (±2 / ±4 / ±8 g) and a 16-bit three-axis magnetometer (fixed ±1200 µT
// range, 0.1 µT/LSB) behind one I²C address (0x1C-0x1F, selected by SA1 /
// SA0) and one shared ADC. In hybrid mode both sensors run at half the
// programmed output data rate and all twelve data bytes can be read in one
// burst. Do not leave SA0 floating: a floating SA0 at power-up selects SPI.
//
// The constructor runs the datasheet initialization sequence: it verifies
// WHO_AM_I (0xC7, or 0xC4 on pre-production parts), performs a software reset
// (the rst write is not acknowledged; the resulting error is ignored) and
// starts hybrid mode.
//
// Default configuration: hybrid mode, system ODR 200 Hz (100 Hz per sensor),
// ±2 g (4096 counts/g), 14-bit output, normal accelerometer oversampling,
// magnetometer OSR 7, low-noise off, hyb_autoinc_mode on, no FIFO, no
// detectors, no interrupt routing, interrupts active-low push-pull.
type FXOS8700Minimal struct {
	conn     connection.RegisterConnection
	mode     uint8
	dr       uint8
	fsG      uint8
	mods     uint8
	lnoise   bool
	fRead    bool
	fifoMode uint8
	hyb      bool
}

// NewFXOS8700Minimal creates an FXOS8700Minimal, verifies WHO_AM_I and
// starts hybrid mode. conn must be bound to 0x1C-0x1F.
func NewFXOS8700Minimal(conn connection.RegisterConnection) (*FXOS8700Minimal, error) {
	c := &FXOS8700Minimal{conn: conn}
	if err := c.Init(); err != nil {
		return nil, err
	}
	return c, nil
}

// Init runs the datasheet initialization sequence (also used to resume after
// a software reset). It returns an error if WHO_AM_I is neither 0xC7 nor 0xC4.
func (c *FXOS8700Minimal) Init() error {
	time.Sleep(time.Millisecond)
	who, err := c.whoAmI()
	if err != nil {
		return err
	}
	if who != 0xC7 && who != 0xC4 {
		return fmt.Errorf("fxos8700: unexpected WHO_AM_I 0x%02X", who)
	}
	c.softReset()
	who, err = c.whoAmI()
	if err != nil {
		return err
	}
	if who != 0xC7 && who != 0xC4 {
		return fmt.Errorf("fxos8700: unexpected WHO_AM_I 0x%02X after reset", who)
	}
	for _, w := range [][2]uint8{
		{fxos8700RegCtrl1, 0x00},
		{fxos8700RegMCtrl1, 0x1F},
		{fxos8700RegMCtrl2, fxos8700MCtrl2HybAutoin},
		{fxos8700RegXyzDataCfg, 0x00},
		{fxos8700RegCtrl2, 0x00},
		{fxos8700RegCtrl1, 0x11},
	} {
		if err := c.writeReg(w[0], w[1]); err != nil {
			return err
		}
	}
	c.mode, c.dr, c.fsG, c.mods = 2, 2, 2, 0
	c.lnoise, c.fRead, c.fifoMode, c.hyb = false, false, 0, true
	time.Sleep(time.Duration(c.turnOnMs()) * time.Millisecond)
	return nil
}

// ReadAccel reads the acceleration (one burst read of 0x01-0x06) in g.
func (c *FXOS8700Minimal) ReadAccel() (x, y, z float32, err error) {
	rx, ry, rz, err := c.readAccelCounts()
	if err != nil {
		return 0, 0, 0, err
	}
	s := c.accelScale()
	return float32(rx) / s, float32(ry) / s, float32(rz) / s, nil
}

// ReadMag reads the magnetic flux density in µT (one burst read of
// 0x33-0x38; reading M_OUT_X_MSB first latches the X / Y / Z set).
func (c *FXOS8700Minimal) ReadMag() (x, y, z float32, err error) {
	rx, ry, rz, err := c.readMagCounts()
	if err != nil {
		return 0, 0, 0, err
	}
	return float32(rx) * 0.1, float32(ry) * 0.1, float32(rz) * 0.1, nil
}

func (c *FXOS8700Minimal) readReg(reg uint8) (uint8, error) {
	b, err := c.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (c *FXOS8700Minimal) writeReg(reg, value uint8) error {
	return c.conn.WriteReg(uint32(reg), []byte{value})
}

// modify clears mask in the register and ORs in value.
func (c *FXOS8700Minimal) modify(reg, mask, value uint8) error {
	cur, err := c.readReg(reg)
	if err != nil {
		return err
	}
	return c.writeReg(reg, (cur&^mask)|(value&mask))
}

// whoAmI reads WHO_AM_I, retrying on a bus error for up to 10 ms.
func (c *FXOS8700Minimal) whoAmI() (uint8, error) {
	for i := 0; i < 10; i++ {
		if v, err := c.readReg(fxos8700RegWhoAmI); err == nil {
			return v, nil
		}
		time.Sleep(time.Millisecond)
	}
	return c.readReg(fxos8700RegWhoAmI)
}

// softReset writes CTRL_REG2.rst; the chip does not acknowledge it, so the
// bus error is ignored. It waits 2 ms.
func (c *FXOS8700Minimal) softReset() {
	_ = c.writeReg(fxos8700RegCtrl2, fxos8700Ctrl2Rst)
	time.Sleep(2 * time.Millisecond)
}

func (c *FXOS8700Minimal) accelScale() float32 {
	cpg := float32(1024)
	switch c.fsG {
	case 2:
		cpg = 4096
	case 4:
		cpg = 2048
	}
	if c.fRead {
		return cpg / 64
	}
	return cpg
}

func (c *FXOS8700Minimal) decodeAccel(d []byte, off int) (int16, int16, int16) {
	if c.fRead {
		return int16(int8(d[off])), int16(int8(d[off+1])), int16(int8(d[off+2]))
	}
	axis := func(i int) int16 {
		raw := uint32(d[off+2*i])<<8 | uint32(d[off+2*i+1])
		return int16(connection.ToSigned(raw, 16) >> 2)
	}
	return axis(0), axis(1), axis(2)
}

func fxos8700DecodeMag(d []byte, off int) (int16, int16, int16) {
	axis := func(i int) int16 {
		raw := uint32(d[off+2*i])<<8 | uint32(d[off+2*i+1])
		return int16(connection.ToSigned(raw, 16))
	}
	return axis(0), axis(1), axis(2)
}

func (c *FXOS8700Minimal) readAccelCounts() (int16, int16, int16, error) {
	n := 6
	if c.fRead {
		n = 3
	}
	d, err := c.conn.ReadReg(uint32(fxos8700RegOutXMsb), n)
	if err != nil {
		return 0, 0, 0, err
	}
	x, y, z := c.decodeAccel(d, 0)
	return x, y, z, nil
}

func (c *FXOS8700Minimal) readMagCounts() (int16, int16, int16, error) {
	d, err := c.conn.ReadReg(uint32(fxos8700RegMOutXMsb), 6)
	if err != nil {
		return 0, 0, 0, err
	}
	x, y, z := fxos8700DecodeMag(d, 0)
	return x, y, z, nil
}

// turnOnMs is the STANDBY -> ACTIVE turn-on time, 2 / ODR + 2 ms (rounded up).
func (c *FXOS8700Minimal) turnOnMs() int {
	return int(2000/fxos8700OdrHz[c.dr]) + 3
}

// FXOS8700Full is the full FXOS8700 interface — the complete chip
// functionality. It extends FXOS8700Minimal.
//
// Methods that change a STANDBY-only register put the chip into STANDBY,
// write, and restore ACTIVE internally, so the caller never manages the
// mode. Every such change flushes the FIFO and resets the detectors'
// debounce counters.
//
// The vector-magnitude detectors (0x5F-0x72), the magnetic threshold detector
// (0x52-0x5A) and the per-axis freefall / motion thresholds (0x73-0x78) are
// not wrapped: use ReadRegister / WriteRegister.
type FXOS8700Full struct {
	*FXOS8700Minimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewFXOS8700Full creates an FXOS8700Full and runs the initialization sequence.
func NewFXOS8700Full(conn connection.RegisterConnection) (*FXOS8700Full, error) {
	m, err := NewFXOS8700Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &FXOS8700Full{FXOS8700Minimal: m}, nil
}

// FXOS8700PulseConfig configures the tap (pulse) detector for SetPulse.
type FXOS8700PulseConfig struct {
	// Single is the axis mask (X = 1, Y = 2, Z = 4) for single-tap detection.
	Single uint8
	// Double is the axis mask for double-tap detection.
	Double uint8
	// ThresholdG holds the (x, y, z) thresholds in g (0.063 g/LSB).
	ThresholdG [3]float32
	// TimeLimitMs is the pulse time limit in ms.
	TimeLimitMs float32
	// LatencyMs is the latency between the two taps in ms.
	LatencyMs float32
	// WindowMs is the window for the second tap in ms.
	WindowMs float32
	// Latch holds the event until PULSE_SRC is read.
	Latch bool
	// DoubleAbort aborts double-tap detection on a pulse during the latency window.
	DoubleAbort bool
	// LpfOff disables the pulse low-pass filter (enabled by default).
	LpfOff bool
	// HpfOff disables the pulse high-pass filter (enabled by default).
	HpfOff bool
}

// DefaultFXOS8700PulseConfig returns the default tap configuration: 0.5 g
// thresholds, 30 ms time limit, 100 ms latency, 300 ms window, both filters on.
func DefaultFXOS8700PulseConfig() FXOS8700PulseConfig {
	return FXOS8700PulseConfig{
		ThresholdG:  [3]float32{0.5, 0.5, 0.5},
		TimeLimitMs: 30,
		LatencyMs:   100,
		WindowMs:    300,
	}
}

// waitStandby polls SYSMOD until it reads 00 (up to 350 ms).
func (c *FXOS8700Full) waitStandby() error {
	for i := 0; i < 350; i++ {
		v, err := c.readReg(fxos8700RegSysmod)
		if err != nil {
			return err
		}
		if v&0x03 == 0 {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return fmt.Errorf("fxos8700: did not enter STANDBY")
}

// Standby enters STANDBY (active = 0) and waits until SYSMOD reads 0 (up to
// 300 ms when leaving mag-only / hybrid mode).
func (c *FXOS8700Full) Standby() error {
	c1, err := c.readReg(fxos8700RegCtrl1)
	if err != nil {
		return err
	}
	if err := c.writeReg(fxos8700RegCtrl1, c1&^fxos8700Ctrl1Active); err != nil {
		return err
	}
	return c.waitStandby()
}

// Activate enters ACTIVE (active = 1) and waits the turn-on time.
func (c *FXOS8700Full) Activate() error {
	c1, err := c.readReg(fxos8700RegCtrl1)
	if err != nil {
		return err
	}
	if err := c.writeReg(fxos8700RegCtrl1, c1|fxos8700Ctrl1Active); err != nil {
		return err
	}
	time.Sleep(time.Duration(c.turnOnMs()) * time.Millisecond)
	return nil
}

// begin enters STANDBY if ACTIVE and reports whether the chip was ACTIVE.
func (c *FXOS8700Full) begin() (bool, error) {
	c1, err := c.readReg(fxos8700RegCtrl1)
	if err != nil {
		return false, err
	}
	if c1&fxos8700Ctrl1Active != 0 {
		if err := c.writeReg(fxos8700RegCtrl1, c1&^fxos8700Ctrl1Active); err != nil {
			return false, err
		}
		if err := c.waitStandby(); err != nil {
			return false, err
		}
		return true, nil
	}
	return false, nil
}

// end restores ACTIVE if wasActive.
func (c *FXOS8700Full) end(wasActive bool) error {
	if wasActive {
		return c.Activate()
	}
	return nil
}

// inStandby runs fn between begin and end.
func (c *FXOS8700Full) inStandby(fn func() error) error {
	was, err := c.begin()
	if err != nil {
		return err
	}
	if err := fn(); err != nil {
		return err
	}
	return c.end(was)
}

// Reset performs a software reset (CTRL_REG2.rst). The bus error of the
// unacknowledged write is ignored; every register returns to its reset
// value. Call Init afterwards.
func (c *FXOS8700Full) Reset() error {
	c.softReset()
	c.mode, c.dr, c.fsG, c.mods = 0, 0, 2, 0
	c.lnoise, c.fRead, c.fifoMode, c.hyb = false, false, 0, false
	return nil
}

// SetMode selects accelerometer-only (0), magnetometer-only (1) or hybrid
// (2) operation; hybrid also enables hyb_autoinc_mode.
func (c *FXOS8700Full) SetMode(mode uint8) error {
	if mode > 2 {
		return fmt.Errorf("fxos8700: mode must be 0, 1 or 2")
	}
	return c.inStandby(func() error {
		if err := c.modify(fxos8700RegMCtrl1, fxos8700MCtrl1HmsMask, [3]uint8{0, 1, 3}[mode]); err != nil {
			return err
		}
		if mode == 2 {
			if err := c.modify(fxos8700RegMCtrl2, fxos8700MCtrl2HybAutoin, fxos8700MCtrl2HybAutoin); err != nil {
				return err
			}
			c.hyb = true
		}
		c.mode = mode
		return nil
	})
}

// SetRange selects the accelerometer range (2, 4 or 8 g). It is rejected for
// any other value and for 8 g while low-noise mode is on.
func (c *FXOS8700Full) SetRange(rangeG uint8) error {
	if rangeG != 2 && rangeG != 4 && rangeG != 8 {
		return fmt.Errorf("fxos8700: rangeG must be 2, 4 or 8")
	}
	if rangeG == 8 && c.lnoise {
		return fmt.Errorf("fxos8700: 8 g is not available in low-noise mode")
	}
	fs := map[uint8]uint8{2: 0, 4: 1, 8: 2}[rangeG]
	if err := c.modify(fxos8700RegXyzDataCfg, 0x03, fs); err != nil {
		return err
	}
	c.fsG = rangeG
	return nil
}

// SetDataRate selects the system output data rate: 800, 400, 200, 100, 50,
// 12.5, 6.25 or 1.5625 Hz (1.56 accepted). Each sensor runs at half this
// rate in hybrid mode.
func (c *FXOS8700Full) SetDataRate(rateHz float32) error {
	code := -1
	for i, hz := range fxos8700OdrHz {
		if math.Abs(float64(hz-rateHz)) <= 0.01*float64(hz) {
			code = i
		}
	}
	if code < 0 {
		return fmt.Errorf("fxos8700: unsupported data rate")
	}
	return c.inStandby(func() error {
		if err := c.modify(fxos8700RegCtrl1, fxos8700Ctrl1DrMask, uint8(code)<<3); err != nil {
			return err
		}
		c.dr = uint8(code)
		return nil
	})
}

// SetAccelOversampling selects the accelerometer oversampling mode: 0
// normal, 1 low-noise low-power, 2 high resolution, 3 low power. sleepMode
// (same encoding; -1 = unchanged) selects the SLEEP-mode oversampling.
func (c *FXOS8700Full) SetAccelOversampling(mode uint8, sleepMode int) error {
	if mode > 3 || sleepMode > 3 || sleepMode < -1 {
		return fmt.Errorf("fxos8700: oversampling mode must be 0-3")
	}
	return c.inStandby(func() error {
		if err := c.modify(fxos8700RegCtrl2, fxos8700Ctrl2ModsMask, mode); err != nil {
			return err
		}
		if sleepMode >= 0 {
			if err := c.modify(fxos8700RegCtrl2, fxos8700Ctrl2SmodsMask, uint8(sleepMode)<<3); err != nil {
				return err
			}
		}
		c.mods = mode
		return nil
	})
}

// SetMagOversampling selects the magnetometer oversampling ratio code (m_os,
// 0-7; datasheet Table 203) and optionally the Auto-SLEEP code (m_aslp_os;
// -1 = unchanged).
func (c *FXOS8700Full) SetMagOversampling(ratio uint8, sleepRatio int) error {
	if ratio > 7 || sleepRatio > 7 || sleepRatio < -1 {
		return fmt.Errorf("fxos8700: oversampling ratio must be 0-7")
	}
	if err := c.modify(fxos8700RegMCtrl1, fxos8700MCtrl1OsMask, ratio<<2); err != nil {
		return err
	}
	if sleepRatio >= 0 {
		return c.modify(fxos8700RegMCtrl3, 0x70, uint8(sleepRatio)<<4)
	}
	return nil
}

// SetLowNoise enables or disables the reduced-noise mode (limits the range
// to ±4 g). It is rejected when enabling at ±8 g.
func (c *FXOS8700Full) SetLowNoise(enabled bool) error {
	if enabled && c.fsG == 8 {
		return fmt.Errorf("fxos8700: low-noise mode is not available at 8 g")
	}
	return c.inStandby(func() error {
		var v uint8
		if enabled {
			v = fxos8700Ctrl1Lnoise
		}
		if err := c.modify(fxos8700RegCtrl1, fxos8700Ctrl1Lnoise, v); err != nil {
			return err
		}
		c.lnoise = enabled
		return nil
	})
}

// SetResolution selects 14-bit (14) or 8-bit fast-read (8) accelerometer
// output. It is rejected for another value and while the FIFO is on.
func (c *FXOS8700Full) SetResolution(bits uint8) error {
	if bits != 14 && bits != 8 {
		return fmt.Errorf("fxos8700: bits must be 14 or 8")
	}
	if c.fifoMode != 0 {
		return fmt.Errorf("fxos8700: resolution cannot change while the FIFO is on")
	}
	return c.inStandby(func() error {
		var v uint8
		if bits == 8 {
			v = fxos8700Ctrl1FRead
		}
		if err := c.modify(fxos8700RegCtrl1, fxos8700Ctrl1FRead, v); err != nil {
			return err
		}
		c.fRead = bits == 8
		return nil
	})
}

// ReadRawAccel reads the raw accelerometer counts (14-bit right-aligned, or 8-bit).
func (c *FXOS8700Full) ReadRawAccel() (int16, int16, int16, error) {
	return c.readAccelCounts()
}

// ReadRawMag reads the raw magnetometer counts (16-bit, 0.1 µT/LSB).
func (c *FXOS8700Full) ReadRawMag() (int16, int16, int16, error) {
	return c.readMagCounts()
}

// ReadHybrid reads both sensors with one burst from 0x01: acceleration in g
// and magnetic field in µT. It returns an error when the chip is not in
// hybrid mode.
func (c *FXOS8700Full) ReadHybrid() (accel, mag [3]float32, err error) {
	if c.mode != 2 || !c.hyb {
		return accel, mag, fmt.Errorf("fxos8700: ReadHybrid needs hybrid mode with hyb_autoinc_mode")
	}
	nAccel := 6
	if c.fRead {
		nAccel = 3
	}
	d, err := c.conn.ReadReg(uint32(fxos8700RegOutXMsb), nAccel+6)
	if err != nil {
		return accel, mag, err
	}
	ax, ay, az := c.decodeAccel(d, 0)
	mx, my, mz := fxos8700DecodeMag(d, nAccel)
	s := c.accelScale()
	accel = [3]float32{float32(ax) / s, float32(ay) / s, float32(az) / s}
	mag = [3]float32{float32(mx) * 0.1, float32(my) * 0.1, float32(mz) * 0.1}
	return accel, mag, nil
}

// ReadTemperature reads the die temperature in °C (int8(TEMP) × 0.96;
// relative and uncalibrated). It returns an error in accelerometer-only mode.
func (c *FXOS8700Full) ReadTemperature() (float32, error) {
	if c.mode == 0 {
		return 0, fmt.Errorf("fxos8700: temperature needs the magnetometer to be active")
	}
	v, err := c.readReg(fxos8700RegTemp)
	if err != nil {
		return 0, err
	}
	return float32(int8(v)) * 0.96, nil
}

// NewAccelData reports whether a new accelerometer sample is ready
// (STATUS.zyxdr; invalid while the FIFO is on).
func (c *FXOS8700Full) NewAccelData() (bool, error) {
	v, err := c.readReg(fxos8700RegStatus)
	return v&fxos8700StatusZyxdr != 0, err
}

// NewMagData reports whether a new magnetometer sample is ready (M_DR_STATUS.zyxdr).
func (c *FXOS8700Full) NewMagData() (bool, error) {
	v, err := c.readReg(fxos8700RegMDrStatus)
	return v&fxos8700StatusZyxdr != 0, err
}

// ReadStatus reads the STATUS byte.
func (c *FXOS8700Full) ReadStatus() (uint8, error) { return c.readReg(fxos8700RegStatus) }

// ReadMagStatus reads the M_DR_STATUS byte.
func (c *FXOS8700Full) ReadMagStatus() (uint8, error) { return c.readReg(fxos8700RegMDrStatus) }

// ReadSystemMode reads SYSMOD bits 1:0 (0 standby, 1 wake, 2 sleep).
func (c *FXOS8700Full) ReadSystemMode() (uint8, error) {
	v, err := c.readReg(fxos8700RegSysmod)
	return v & 0x03, err
}

// SetHighPassOutput selects high-pass-filtered output data (hpf_out) and the
// cutoff code (sel, 0-3; datasheet Table 64).
func (c *FXOS8700Full) SetHighPassOutput(enabled bool, cutoff uint8) error {
	if cutoff > 3 {
		return fmt.Errorf("fxos8700: cutoff must be 0-3")
	}
	return c.inStandby(func() error {
		var v uint8
		if enabled {
			v = 0x10
		}
		if err := c.modify(fxos8700RegXyzDataCfg, 0x10, v); err != nil {
			return err
		}
		return c.modify(fxos8700RegHpFilterCut, 0x03, cutoff)
	})
}

// SetAccelOffset writes the per-axis accelerometer offsets in g (added to
// the output, 2 mg/LSB, ±0.256 g).
func (c *FXOS8700Full) SetAccelOffset(xG, yG, zG float32) error {
	enc := func(v float32) byte {
		r := int(math.Round(float64(v) / 0.002))
		if r < -128 {
			r = -128
		}
		if r > 127 {
			r = 127
		}
		return byte(int8(r))
	}
	data := []byte{enc(xG), enc(yG), enc(zG)}
	return c.inStandby(func() error { return c.conn.WriteReg(uint32(fxos8700RegOffX), data) })
}

// GetAccelOffset reads the accelerometer offsets in g.
func (c *FXOS8700Full) GetAccelOffset() (x, y, z float32, err error) {
	d, err := c.conn.ReadReg(uint32(fxos8700RegOffX), 3)
	if err != nil {
		return 0, 0, 0, err
	}
	return float32(int8(d[0])) * 0.002, float32(int8(d[1])) * 0.002, float32(int8(d[2])) * 0.002, nil
}

// SetMagOffset writes the hard-iron offsets in µT (15-bit value in bits
// 15:1, subtracted from each measurement, 0.1 µT/LSB).
func (c *FXOS8700Full) SetMagOffset(xUt, yUt, zUt float32) error {
	data := make([]byte, 6)
	for i, v := range [3]float32{xUt, yUt, zUt} {
		r := int(math.Round(float64(v) / 0.1))
		if r < -16384 {
			r = -16384
		}
		if r > 16383 {
			r = 16383
		}
		word := uint16((r << 1) & 0xFFFF)
		data[2*i] = byte(word >> 8)
		data[2*i+1] = byte(word & 0xFF)
	}
	return c.conn.WriteReg(uint32(fxos8700RegMOffXMsb), data)
}

// GetMagOffset reads the hard-iron offsets in µT.
func (c *FXOS8700Full) GetMagOffset() (x, y, z float32, err error) {
	d, err := c.conn.ReadReg(uint32(fxos8700RegMOffXMsb), 6)
	if err != nil {
		return 0, 0, 0, err
	}
	axis := func(i int) float32 {
		word := uint32(d[2*i])<<8 | uint32(d[2*i+1])
		return float32(connection.ToSigned(word, 16)>>1) * 0.1
	}
	return axis(0), axis(1), axis(2), nil
}

// SetMagRaw selects raw magnetometer output (m_raw): true = the hard-iron
// offsets are not applied to the output.
func (c *FXOS8700Full) SetMagRaw(raw bool) error {
	var v uint8
	if raw {
		v = 0x80
	}
	return c.modify(fxos8700RegMCtrl3, 0x80, v)
}

// SetMagAutoCalibration enables or disables hardware hard-iron
// auto-calibration (m_acal). Enabling also clears m_raw and enables min/max
// tracking. Rotate the board through all orientations while on; the offsets
// stay in M_OFF_* afterwards (NXP AN4399).
func (c *FXOS8700Full) SetMagAutoCalibration(enabled bool) error {
	if enabled {
		if err := c.modify(fxos8700RegMCtrl3, 0x80, 0); err != nil {
			return err
		}
		if err := c.inStandby(func() error { return c.modify(fxos8700RegMCtrl2, 0x10, 0) }); err != nil {
			return err
		}
	}
	var v uint8
	if enabled {
		v = fxos8700MCtrl1Acal
	}
	return c.modify(fxos8700RegMCtrl1, fxos8700MCtrl1Acal, v)
}

// ReadMagMinMax reads the tracked magnetic minimum and maximum in µT.
func (c *FXOS8700Full) ReadMagMinMax() (minUt, maxUt [3]float32, err error) {
	d, err := c.conn.ReadReg(uint32(fxos8700RegMinXMsb), 6)
	if err != nil {
		return minUt, maxUt, err
	}
	x, y, z := fxos8700DecodeMag(d, 0)
	minUt = [3]float32{float32(x) * 0.1, float32(y) * 0.1, float32(z) * 0.1}
	d, err = c.conn.ReadReg(uint32(fxos8700RegMaxXMsb), 6)
	if err != nil {
		return minUt, maxUt, err
	}
	x, y, z = fxos8700DecodeMag(d, 0)
	maxUt = [3]float32{float32(x) * 0.1, float32(y) * 0.1, float32(z) * 0.1}
	return minUt, maxUt, nil
}

// ResetMagMinMax resets the min/max registers to 0x7FFF / 0x8000 (m_maxmin_rst).
func (c *FXOS8700Full) ResetMagMinMax() error {
	return c.modify(fxos8700RegMCtrl2, 0x04, 0x04)
}

// SetMagReset selects the automatic magnetic reset (degauss) period: 0 every
// ODR cycle, 1 every 16, 2 every 512, 3 disabled.
func (c *FXOS8700Full) SetMagReset(mode uint8) error {
	if mode > 3 {
		return fmt.Errorf("fxos8700: mode must be 0-3")
	}
	return c.inStandby(func() error { return c.modify(fxos8700RegMCtrl2, 0x03, mode) })
}

// TriggerMagReset triggers a one-shot magnetic reset (m_rst).
func (c *FXOS8700Full) TriggerMagReset() error {
	return c.modify(fxos8700RegMCtrl1, fxos8700MCtrl1Rst, fxos8700MCtrl1Rst)
}

// MeasureMagOnce takes one magnetic measurement in STANDBY (m_ost) and
// restores ACTIVE. It returns an error in accelerometer-only mode or on a
// 1 s timeout.
func (c *FXOS8700Full) MeasureMagOnce() (x, y, z float32, err error) {
	if c.mode == 0 {
		return 0, 0, 0, fmt.Errorf("fxos8700: one-shot measurement needs the magnetometer")
	}
	was, err := c.begin()
	if err != nil {
		return 0, 0, 0, err
	}
	if err := c.modify(fxos8700RegMCtrl1, fxos8700MCtrl1Ost, fxos8700MCtrl1Ost); err != nil {
		return 0, 0, 0, err
	}
	done := false
	for i := 0; i < 1000; i++ {
		v, err := c.readReg(fxos8700RegMCtrl1)
		if err != nil {
			return 0, 0, 0, err
		}
		if v&fxos8700MCtrl1Ost == 0 {
			done = true
			break
		}
		time.Sleep(time.Millisecond)
	}
	if !done {
		_ = c.end(was)
		return 0, 0, 0, fmt.Errorf("fxos8700: magnetic one-shot measurement timed out")
	}
	x, y, z, err = c.ReadMag()
	if err != nil {
		return 0, 0, 0, err
	}
	return x, y, z, c.end(was)
}

// Heading computes the tilt-compensated compass heading in degrees,
// [0, 360) (chip frame X forward, Y right, Z down; verify the axis signs on
// hardware). declinationDeg is added to the result.
func (c *FXOS8700Full) Heading(declinationDeg float32) (float32, error) {
	var a, m [3]float32
	if c.mode == 2 && c.hyb {
		var err error
		if a, m, err = c.ReadHybrid(); err != nil {
			return 0, err
		}
	} else {
		var err error
		if a[0], a[1], a[2], err = c.ReadAccel(); err != nil {
			return 0, err
		}
		if m[0], m[1], m[2], err = c.ReadMag(); err != nil {
			return 0, err
		}
	}
	ax, ay, az := float64(a[0]), float64(a[1]), float64(a[2])
	mx, my, mz := float64(m[0]), float64(m[1]), float64(m[2])
	roll := math.Atan2(ay, az)
	pitch := math.Atan2(-ax, ay*math.Sin(roll)+az*math.Cos(roll))
	mx2 := mx*math.Cos(pitch) + mz*math.Sin(pitch)
	my2 := mx*math.Sin(roll)*math.Sin(pitch) + my*math.Cos(roll) - mz*math.Sin(roll)*math.Cos(pitch)
	deg := math.Atan2(-my2, mx2)*180/math.Pi + float64(declinationDeg)
	deg = math.Mod(deg, 360)
	if deg < 0 {
		deg += 360
	}
	return float32(deg), nil
}

// SetFifo configures the 32-sample accelerometer FIFO: mode 0 disabled, 1
// circular, 2 fill, 3 trigger; watermark 0-63. Changing between two enabled
// modes goes through disabled. The FIFO holds accelerometer data only.
func (c *FXOS8700Full) SetFifo(mode, watermark uint8) error {
	if mode > 3 || watermark > 63 {
		return fmt.Errorf("fxos8700: invalid FIFO mode or watermark")
	}
	return c.inStandby(func() error {
		if c.fifoMode != 0 && mode != 0 && mode != c.fifoMode {
			if err := c.writeReg(fxos8700RegFSetup, 0x00); err != nil {
				return err
			}
		}
		if err := c.writeReg(fxos8700RegFSetup, mode<<6|watermark); err != nil {
			return err
		}
		c.fifoMode = mode
		return nil
	})
}

// SetFifoTrigger selects the FIFO trigger sources (TRIG_CFG).
func (c *FXOS8700Full) SetFifoTrigger(transient, orientation, pulse, motion bool) error {
	var v uint8
	if transient {
		v |= 0x20
	}
	if orientation {
		v |= 0x10
	}
	if pulse {
		v |= 0x08
	}
	if motion {
		v |= 0x04
	}
	return c.inStandby(func() error { return c.writeReg(fxos8700RegTrigCfg, v) })
}

// FifoLevel returns the number of samples in the FIFO (0-32).
func (c *FXOS8700Full) FifoLevel() (uint8, error) {
	v, err := c.readReg(fxos8700RegStatus)
	return v & 0x3F, err
}

// ReadFifoStatus decodes F_STATUS (clears SourceFifo).
func (c *FXOS8700Full) ReadFifoStatus() (overflow, watermark bool, count uint8, err error) {
	v, err := c.readReg(fxos8700RegStatus)
	return v&0x80 != 0, v&0x40 != 0, v & 0x3F, err
}

// ReadFifo drains up to count accelerometer samples (count <= 0: all) from
// the FIFO as (x, y, z) triples in g, one 6-byte (3 in 8-bit mode)
// transaction per sample so the hybrid auto-increment jump to the
// magnetometer never matters.
func (c *FXOS8700Full) ReadFifo(count int) ([][3]float32, error) {
	level, err := c.FifoLevel()
	if err != nil {
		return nil, err
	}
	n := int(level)
	if count > 0 && count < n {
		n = count
	}
	s := c.accelScale()
	out := make([][3]float32, 0, n)
	for i := 0; i < n; i++ {
		x, y, z, err := c.readAccelCounts()
		if err != nil {
			return nil, err
		}
		out = append(out, [3]float32{float32(x) / s, float32(y) / s, float32(z) / s})
	}
	return out, nil
}

// SetFifoGate enables or disables the FIFO gate (fifo_gate).
func (c *FXOS8700Full) SetFifoGate(enabled bool) error {
	var v uint8
	if enabled {
		v = 0x80
	}
	return c.inStandby(func() error { return c.modify(fxos8700RegCtrl3, 0x80, v) })
}

func (c *FXOS8700Full) row() int {
	r := int(c.dr)
	if c.mode == 2 {
		r++
	}
	if r > 7 {
		r = 7
	}
	return r
}

func (c *FXOS8700Full) stepMs() float32 { return fxos8700StepMs[c.mods][c.row()] }

func (c *FXOS8700Full) pulseStepMs(lpf bool) float32 {
	if lpf {
		return fxos8700StepMs[c.mods][c.row()]
	}
	return fxos8700PulseStepNoLpfMs[c.mods][c.row()]
}

func fxos8700Threshold(g float32) uint8 {
	r := int(math.Round(float64(g) / 0.063))
	if r < 0 {
		r = 0
	}
	if r > 127 {
		r = 127
	}
	return uint8(r)
}

func fxos8700Count(timeMs, stepMs float32) uint8 {
	r := int(math.Round(float64(timeMs) / float64(stepMs)))
	if r < 0 {
		r = 0
	}
	if r > 255 {
		r = 255
	}
	return uint8(r)
}

// SetFreefallMotion configures the freefall / motion detector (A_FFMT_*).
// motion = true detects motion (any enabled axis above the threshold), false
// freefall (all below). axes is a mask (X = 1, Y = 2, Z = 4); latch holds
// the event until A_FFMT_SRC is read; debounceClear clears (instead of
// decrements) the debounce counter when the condition is lost.
func (c *FXOS8700Full) SetFreefallMotion(motion bool, axes uint8, thresholdG, debounceMs float32, latch, debounceClear bool) error {
	cfg := (axes & 7) << 3
	if latch {
		cfg |= 0x80
	}
	if motion {
		cfg |= 0x40
	}
	ths := fxos8700Threshold(thresholdG)
	if debounceClear {
		ths |= 0x80
	}
	count := fxos8700Count(debounceMs, c.stepMs())
	return c.inStandby(func() error {
		for _, w := range [][2]uint8{{fxos8700RegAFfmtCfg, cfg}, {fxos8700RegAFfmtThs, ths}, {fxos8700RegAFfmtCount, count}} {
			if err := c.writeReg(w[0], w[1]); err != nil {
				return err
			}
		}
		return nil
	})
}

// ReadMotionSource reads A_FFMT_SRC (clears the flags when latched).
func (c *FXOS8700Full) ReadMotionSource() (uint8, error) { return c.readReg(fxos8700RegAFfmtSrc) }

// SetTransient configures the transient detector (TRANSIENT_*). axes is a
// mask (X = 1, Y = 2, Z = 4); bypassHpf makes it behave like motion
// detection; latch holds the event until TRANSIENT_SRC is read.
func (c *FXOS8700Full) SetTransient(axes uint8, thresholdG, debounceMs float32, bypassHpf, latch bool) error {
	cfg := (axes & 7) << 1
	if latch {
		cfg |= 0x10
	}
	if bypassHpf {
		cfg |= 1
	}
	count := fxos8700Count(debounceMs, c.stepMs())
	return c.inStandby(func() error {
		for _, w := range [][2]uint8{{fxos8700RegTransientCfg, cfg}, {fxos8700RegTransientThs, fxos8700Threshold(thresholdG)}, {fxos8700RegTransientCnt, count}} {
			if err := c.writeReg(w[0], w[1]); err != nil {
				return err
			}
		}
		return nil
	})
}

// ReadTransientSource reads TRANSIENT_SRC — clears the interrupt.
func (c *FXOS8700Full) ReadTransientSource() (uint8, error) {
	return c.readReg(fxos8700RegTransientSrc)
}

// SetPulse configures tap (pulse) detection (PULSE_*); see FXOS8700PulseConfig.
func (c *FXOS8700Full) SetPulse(cfg FXOS8700PulseConfig) error {
	var reg uint8
	if cfg.DoubleAbort {
		reg |= 0x80
	}
	if cfg.Latch {
		reg |= 0x40
	}
	for i := 0; i < 3; i++ {
		if (cfg.Single>>i)&1 != 0 {
			reg |= 1 << (2 * i)
		}
		if (cfg.Double>>i)&1 != 0 {
			reg |= 2 << (2 * i)
		}
	}
	lpf, hpf := !cfg.LpfOff, !cfg.HpfOff
	step := c.pulseStepMs(lpf)
	tmlt := fxos8700Count(cfg.TimeLimitMs, step)
	ltcy := fxos8700Count(cfg.LatencyMs, 2*step)
	wind := fxos8700Count(cfg.WindowMs, 2*step)
	ths := []byte{fxos8700Threshold(cfg.ThresholdG[0]), fxos8700Threshold(cfg.ThresholdG[1]), fxos8700Threshold(cfg.ThresholdG[2])}
	var hp uint8
	if !hpf {
		hp |= 0x20
	}
	if lpf {
		hp |= 0x10
	}
	return c.inStandby(func() error {
		if err := c.writeReg(fxos8700RegPulseCfg, reg); err != nil {
			return err
		}
		if err := c.conn.WriteReg(uint32(fxos8700RegPulseThsx), ths); err != nil {
			return err
		}
		for _, w := range [][2]uint8{{fxos8700RegPulseTmlt, tmlt}, {fxos8700RegPulseLtcy, ltcy}, {fxos8700RegPulseWind, wind}} {
			if err := c.writeReg(w[0], w[1]); err != nil {
				return err
			}
		}
		return c.modify(fxos8700RegHpFilterCut, 0x30, hp)
	})
}

// ReadPulseSource reads PULSE_SRC — clears the interrupt.
func (c *FXOS8700Full) ReadPulseSource() (uint8, error) { return c.readReg(fxos8700RegPulseSrc) }

// SetOrientation configures orientation (portrait / landscape) detection.
// thresholdDeg is one of 15, 20, 30, 35, 40, 45, 55, 60, 70, 75; hysteresis
// is the hys code 0-7; zLock the Z-lock code 0-7; backFront the back/front
// trip code 0-3. It is rejected for an invalid argument or when pl_ths + hys
// is outside 1-31.
func (c *FXOS8700Full) SetOrientation(enabled bool, debounceMs float32, thresholdDeg, hysteresis, zLock, backFront uint8) error {
	ths, ok := fxos8700PlThresholds[thresholdDeg]
	if !ok {
		return fmt.Errorf("fxos8700: unsupported thresholdDeg")
	}
	if hysteresis > 7 || zLock > 7 || backFront > 3 {
		return fmt.Errorf("fxos8700: hysteresis/zLock/backFront out of range")
	}
	if sum := int(ths) + int(hysteresis); sum < 1 || sum > 31 {
		return fmt.Errorf("fxos8700: pl_ths + hys must be within 1-31")
	}
	count := fxos8700Count(debounceMs, c.stepMs())
	cfg := uint8(0x80)
	if enabled {
		cfg |= 0x40
	}
	return c.inStandby(func() error {
		for _, w := range [][2]uint8{
			{fxos8700RegPlCfg, cfg}, {fxos8700RegPlCount, count},
			{fxos8700RegPlThs, ths<<3 | hysteresis}, {fxos8700RegPlBfZcomp, backFront<<6 | zLock},
		} {
			if err := c.writeReg(w[0], w[1]); err != nil {
				return err
			}
		}
		return nil
	})
}

// ReadOrientation decodes PL_STATUS — clears newlp. portraitLandscape is 0
// portrait up, 1 portrait down, 2 landscape right, 3 landscape left.
func (c *FXOS8700Full) ReadOrientation() (changed, lockout bool, portraitLandscape uint8, back bool, err error) {
	v, err := c.readReg(fxos8700RegPlStatus)
	return v&0x80 != 0, v&0x40 != 0, (v >> 1) & 0x03, v&0x01 != 0, err
}

// SetAutoSleep configures Auto-SLEEP / WAKE: sleepRateHz is 50, 12.5, 6.25 or
// 1.56 (halved in hybrid mode), timeoutS the inactivity time, wakeSources a
// mask of the SourceTransient, SourceOrientation, SourcePulse, SourceFfMt and
// SourceAccelVecm constants.
func (c *FXOS8700Full) SetAutoSleep(enabled bool, sleepRateHz, timeoutS float32, wakeSources uint8) error {
	code := -1
	for _, r := range fxos8700AslpRates {
		if math.Abs(float64(r.hz-sleepRateHz)) <= 0.01*float64(r.hz) {
			code = int(r.code)
		}
	}
	if code < 0 {
		return fmt.Errorf("fxos8700: unsupported sleep rate")
	}
	if wakeSources&^0x3E != 0 {
		return fmt.Errorf("fxos8700: unsupported wake source")
	}
	step := float32(0.32)
	if c.dr == 7 {
		step = 0.64
	}
	count := fxos8700Count(timeoutS, step)
	return c.inStandby(func() error {
		if err := c.modify(fxos8700RegCtrl1, fxos8700Ctrl1AslpMask, uint8(code)<<6); err != nil {
			return err
		}
		if err := c.writeReg(fxos8700RegAslpCount, count); err != nil {
			return err
		}
		var slpe uint8
		if enabled {
			slpe = fxos8700Ctrl2Slpe
		}
		if err := c.modify(fxos8700RegCtrl2, fxos8700Ctrl2Slpe, slpe); err != nil {
			return err
		}
		return c.modify(fxos8700RegCtrl3, 0x7C, (wakeSources&0x3E)<<1)
	})
}

// SetInterruptPins configures the INT1 / INT2 polarity and drive (ipol, pp_od).
func (c *FXOS8700Full) SetInterruptPins(activeHigh, openDrain bool) error {
	var v uint8
	if activeHigh {
		v |= 0x02
	}
	if openDrain {
		v |= 0x01
	}
	return c.inStandby(func() error { return c.modify(fxos8700RegCtrl3, 0x03, v) })
}

// EnableInterrupt enables interrupt source (a mask of the FXOS8700Source*
// constants) and routes it to INT1 (pin 1) or INT2 (pin 2). It only routes
// the source; the detector behind it is configured with the Set* methods.
func (c *FXOS8700Full) EnableInterrupt(source uint8, pin int) error {
	if pin != 1 && pin != 2 {
		return fmt.Errorf("fxos8700: pin must be 1 or 2")
	}
	return c.inStandby(func() error {
		if err := c.modify(fxos8700RegCtrl4, source, source); err != nil {
			return err
		}
		var route uint8
		if pin == 1 {
			route = source
		}
		return c.modify(fxos8700RegCtrl5, source, route)
	})
}

// DisableInterrupt disables interrupt source (clears its int_en_* bit).
func (c *FXOS8700Full) DisableInterrupt(source uint8) error {
	return c.inStandby(func() error { return c.modify(fxos8700RegCtrl4, source, 0) })
}

// PollInterrupt reads INT_SOURCE and clears the pending sources: for every
// set bit except SourceDataReady and SourceAccelVecm the matching source
// register is read so the flag (and the pin) clears. It returns a mask of
// the FXOS8700Source* constants.
func (c *FXOS8700Full) PollInterrupt() (uint8, error) {
	status, err := c.readReg(fxos8700RegIntSource)
	if err != nil {
		return 0, err
	}
	for _, e := range []struct{ bit, reg uint8 }{
		{FXOS8700SourceAutoSleep, fxos8700RegSysmod},
		{FXOS8700SourceFifo, fxos8700RegStatus},
		{FXOS8700SourceTransient, fxos8700RegTransientSrc},
		{FXOS8700SourceOrientation, fxos8700RegPlStatus},
		{FXOS8700SourcePulse, fxos8700RegPulseSrc},
		{FXOS8700SourceFfMt, fxos8700RegAFfmtSrc},
	} {
		if status&e.bit != 0 {
			if _, err := c.readReg(e.reg); err != nil {
				return 0, err
			}
		}
	}
	return status, nil
}

// OnInterrupt subscribes callback to the chip's INT line. The callback
// receives the PollInterrupt mask and is only called for a non-zero status.
// The delivery uses the connection's IntPin when one is wired (falling edge
// for active-low, rising for active-high) and falls back to a 5 ms polling
// goroutine otherwise.
func (c *FXOS8700Full) OnInterrupt(callback func(uint8)) error {
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

	ctrl3, err := c.readReg(fxos8700RegCtrl3)
	if err != nil {
		return err
	}
	trigger := connection.Falling
	if ctrl3&0x02 != 0 {
		trigger = connection.Rising
	}
	pin := c.conn.IntPin()
	if pin == nil {
		poll := connection.NewDefaultPollingInputPin()
		c.mu.Lock()
		c.pollPin = poll
		c.mu.Unlock()
		pin = poll
	}
	unsub := pin.OnEdge(trigger, func() { c.handleEdge() })
	c.mu.Lock()
	c.unsubscribe = unsub
	c.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops the interrupt delivery.
func (c *FXOS8700Full) OffInterrupt() error {
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

// handleEdge reads and clears the interrupt, then dispatches the callback.
func (c *FXOS8700Full) handleEdge() {
	status, err := c.PollInterrupt()
	if err != nil || status == 0 {
		return
	}
	c.mu.Lock()
	callback := c.callback
	c.mu.Unlock()
	if callback != nil {
		callback(status)
	}
}

// ReadRegister reads raw register bytes (vector-magnitude, magnetic-threshold
// and per-axis FFMT registers). No STANDBY handling.
func (c *FXOS8700Full) ReadRegister(reg uint8, length int) ([]byte, error) {
	return c.conn.ReadReg(uint32(reg), length)
}

// WriteRegister writes raw register bytes (at most 16). No STANDBY handling.
func (c *FXOS8700Full) WriteRegister(reg uint8, data []byte) error {
	return c.conn.WriteReg(uint32(reg), data)
}

// averageCounts averages 8 accelerometer readings (accel-only, 14-bit).
func (c *FXOS8700Full) averageCounts() (x, y, z int, err error) {
	var sx, sy, sz int
	for i := 0; i < 8; i++ {
		time.Sleep(12 * time.Millisecond)
		d, err := c.conn.ReadReg(uint32(fxos8700RegOutXMsb), 6)
		if err != nil {
			return 0, 0, 0, err
		}
		cx, cy, cz := c.decodeAccel(d, 0)
		sx += int(cx)
		sy += int(cy)
		sz += int(cz)
	}
	floorDiv := func(v int) int { return int(math.Floor(float64(v) / 8)) }
	return floorDiv(sx), floorDiv(sy), floorDiv(sz), nil
}

// SelfTest runs the accelerometer self-test and restores the configuration.
// STANDBY, ±2 g, 100 Hz accelerometer-only, normal oversampling; the
// difference of two 8-sample averages (before / after st) is checked against
// the datasheet minimum X >= +192, Y >= +270, Z >= +1275 counts. It returns
// ok and the differences in 14-bit counts.
func (c *FXOS8700Full) SelfTest() (ok bool, dx, dy, dz int, err error) {
	saved := map[uint8]uint8{}
	for _, r := range []uint8{fxos8700RegCtrl1, fxos8700RegCtrl2, fxos8700RegXyzDataCfg, fxos8700RegMCtrl1} {
		v, err := c.readReg(r)
		if err != nil {
			return false, 0, 0, 0, err
		}
		saved[r] = v
	}
	mode, dr, fsG, mods, lnoise, fRead := c.mode, c.dr, c.fsG, c.mods, c.lnoise, c.fRead
	if err := c.Standby(); err != nil {
		return false, 0, 0, 0, err
	}
	for _, w := range [][2]uint8{
		{fxos8700RegXyzDataCfg, 0x00},
		{fxos8700RegMCtrl1, saved[fxos8700RegMCtrl1] &^ fxos8700MCtrl1HmsMask},
		{fxos8700RegCtrl2, 0x00},
	} {
		if err := c.writeReg(w[0], w[1]); err != nil {
			return false, 0, 0, 0, err
		}
	}
	c.mode, c.dr, c.fsG, c.mods, c.lnoise, c.fRead = 0, 3, 2, 0, false, false
	if err := c.writeReg(fxos8700RegCtrl1, 3<<3|fxos8700Ctrl1Active); err != nil {
		return false, 0, 0, 0, err
	}
	time.Sleep(time.Duration(c.turnOnMs()) * time.Millisecond)
	bx, by, bz, err := c.averageCounts()
	if err != nil {
		return false, 0, 0, 0, err
	}
	if err := c.writeReg(fxos8700RegCtrl2, fxos8700Ctrl2St); err != nil {
		return false, 0, 0, 0, err
	}
	time.Sleep(25 * time.Millisecond)
	ax, ay, az, err := c.averageCounts()
	if err != nil {
		return false, 0, 0, 0, err
	}
	if err := c.writeReg(fxos8700RegCtrl2, 0x00); err != nil {
		return false, 0, 0, 0, err
	}
	if err := c.Standby(); err != nil {
		return false, 0, 0, 0, err
	}
	for _, w := range [][2]uint8{
		{fxos8700RegXyzDataCfg, saved[fxos8700RegXyzDataCfg]},
		{fxos8700RegMCtrl1, saved[fxos8700RegMCtrl1]},
		{fxos8700RegCtrl2, saved[fxos8700RegCtrl2]},
	} {
		if err := c.writeReg(w[0], w[1]); err != nil {
			return false, 0, 0, 0, err
		}
	}
	c.mode, c.dr, c.fsG, c.mods, c.lnoise, c.fRead = mode, dr, fsG, mods, lnoise, fRead
	if err := c.writeReg(fxos8700RegCtrl1, saved[fxos8700RegCtrl1]); err != nil {
		return false, 0, 0, 0, err
	}
	if saved[fxos8700RegCtrl1]&fxos8700Ctrl1Active != 0 {
		time.Sleep(time.Duration(c.turnOnMs()) * time.Millisecond)
	}
	dx, dy, dz = ax-bx, ay-by, az-bz
	return dx >= 192 && dy >= 270 && dz >= 1275, dx, dy, dz, nil
}

// Package accelerometer contains drivers for standalone accelerometers.
package accelerometer

import (
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LIS3DH register addresses.
const (
	lis3dhRegStatusAux   uint8 = 0x07
	lis3dhRegOutADC1L    uint8 = 0x08
	lis3dhRegOutADC1H    uint8 = 0x09
	lis3dhRegOutADC2L    uint8 = 0x0A
	lis3dhRegOutADC2H    uint8 = 0x0B
	lis3dhRegOutADC3L    uint8 = 0x0C
	lis3dhRegOutADC3H    uint8 = 0x0D
	lis3dhRegWhoAmI      uint8 = 0x0F
	lis3dhRegCtrlReg0    uint8 = 0x1E
	lis3dhRegTempCfg     uint8 = 0x1F
	lis3dhRegCtrlReg1    uint8 = 0x20
	lis3dhRegCtrlReg2    uint8 = 0x21
	lis3dhRegCtrlReg3    uint8 = 0x22
	lis3dhRegCtrlReg4    uint8 = 0x23
	lis3dhRegCtrlReg5    uint8 = 0x24
	lis3dhRegCtrlReg6    uint8 = 0x25
	lis3dhRegReference   uint8 = 0x26
	lis3dhRegStatus      uint8 = 0x27
	lis3dhRegOutXL       uint8 = 0x28
	lis3dhRegOutXH       uint8 = 0x29
	lis3dhRegOutYL       uint8 = 0x2A
	lis3dhRegOutYH       uint8 = 0x2B
	lis3dhRegOutZL       uint8 = 0x2C
	lis3dhRegOutZH       uint8 = 0x2D
	lis3dhRegFifoCtrl    uint8 = 0x2E
	lis3dhRegFifoSrc     uint8 = 0x2F
	lis3dhRegInt1Cfg     uint8 = 0x30
	lis3dhRegInt1Src     uint8 = 0x31
	lis3dhRegInt1Ths     uint8 = 0x32
	lis3dhRegInt1Dur     uint8 = 0x33
	lis3dhRegInt2Cfg     uint8 = 0x34
	lis3dhRegInt2Src     uint8 = 0x35
	lis3dhRegInt2Ths     uint8 = 0x36
	lis3dhRegInt2Dur     uint8 = 0x37
	lis3dhRegClickCfg    uint8 = 0x38
	lis3dhRegClickSrc    uint8 = 0x39
	lis3dhRegClickThs    uint8 = 0x3A
	lis3dhRegTimeLimit   uint8 = 0x3B
	lis3dhRegTimeLatency uint8 = 0x3C
	lis3dhRegTimeWindow  uint8 = 0x3D
	lis3dhRegActThs      uint8 = 0x3E
	lis3dhRegActDur      uint8 = 0x3F
)

// Constants of the initialization sequence and of the identity register.
const (
	// lis3dhWhoAmIValue is the content the WHO_AM_I register (0x0F) must
	// hold for the chip to be recognised.
	lis3dhWhoAmIValue uint8 = 0x33
	// lis3dhCtrlReg4Default is BDU=1, FS=00 (±2 g), HR=1 (high-resolution).
	lis3dhCtrlReg4Default uint8 = 0x88
	// lis3dhCtrlReg1Default is ODR=0101 (100 Hz), LPen=0, X/Y/Z enabled.
	lis3dhCtrlReg1Default uint8 = 0x57
	// lis3dhAutoIncrement is the MSb of the I²C sub-address. Setting it
	// makes the chip auto-increment the register address during a
	// multi-byte transfer.
	lis3dhAutoIncrement uint8 = 0x80
	// lis3dhBootDelay is the time the chip needs for its power-on boot
	// procedure; no register may be accessed before it completed.
	lis3dhBootDelay = 5 * time.Millisecond
)

// LIS3DH operating modes — the resolution / power trade-off.
const (
	// LIS3DHModeLowPower selects the 8-bit low-power mode.
	LIS3DHModeLowPower = 0
	// LIS3DHModeNormal selects the 10-bit normal mode.
	LIS3DHModeNormal = 1
	// LIS3DHModeHighResolution selects the 12-bit high-resolution mode.
	LIS3DHModeHighResolution = 2
)

// LIS3DH full-scale measurement ranges in g.
const (
	// LIS3DHRange2G is the ±2 g full-scale range.
	LIS3DHRange2G = 2
	// LIS3DHRange4G is the ±4 g full-scale range.
	LIS3DHRange4G = 4
	// LIS3DHRange8G is the ±8 g full-scale range.
	LIS3DHRange8G = 8
	// LIS3DHRange16G is the ±16 g full-scale range.
	LIS3DHRange16G = 16
)

// LIS3DH interrupt sources. The value of a source is also the bit position
// it occupies in the combined status returned by PollInterrupt.
//
// SourceActivity and SourceBoot are pin-only: the chip has no register
// flag for them, so PollInterrupt never reports them.
const (
	// SourceDataReady is a new X/Y/Z sample set (STATUS_REG.ZYXDA).
	SourceDataReady uint16 = 0x0001
	// SourceAuxDataReady is a new auxiliary ADC sample (321DA).
	SourceAuxDataReady uint16 = 0x0002
	// SourceWatermark is the FIFO watermark reached (FIFO_SRC_REG.WTM).
	SourceWatermark uint16 = 0x0004
	// SourceOverrun is a FIFO overrun (FIFO_SRC_REG.OVRN_FIFO).
	SourceOverrun uint16 = 0x0008
	// SourceIA1 is an inertial interrupt generator 1 event (INT1_SRC.IA).
	SourceIA1 uint16 = 0x0010
	// SourceIA2 is an inertial interrupt generator 2 event (INT2_SRC.IA).
	SourceIA2 uint16 = 0x0020
	// SourceClick is a single or double click (CLICK_SRC.IA).
	SourceClick uint16 = 0x0040
	// SourceActivity is the sleep-to-wake / return-to-sleep activity event.
	SourceActivity uint16 = 0x0080
	// SourceBoot is the boot procedure running indication.
	SourceBoot uint16 = 0x0100
)

// lis3dhRanges lists the supported full-scale ranges in g.
var lis3dhRanges = []int{LIS3DHRange2G, LIS3DHRange4G, LIS3DHRange8G, LIS3DHRange16G}

// lis3dhRateCodes maps an output data rate in Hz to its CTRL_REG1 ODR<3:0>
// code. 1344 Hz and 5376 Hz share the 1001 code; the operating mode
// disambiguates them.
var lis3dhRateCodes = map[int]uint8{
	0:    0x00,
	1:    0x10,
	10:   0x20,
	25:   0x30,
	50:   0x40,
	100:  0x50,
	200:  0x60,
	400:  0x70,
	1344: 0x90,
	1620: 0x80,
	5376: 0x90,
}

// lis3dhRateLowPowerOnly lists the rates that only exist in low-power mode.
var lis3dhRateLowPowerOnly = []int{1620, 5376}

// lis3dhRateNonLowPowerOnly lists the rates that do not exist in low-power
// mode.
var lis3dhRateNonLowPowerOnly = []int{1344}

// lis3dhRound rounds half away from zero, so every language computes the
// same register codes.
func lis3dhRound(x float64) int {
	if x >= 0 {
		return int(math.Floor(x + 0.5))
	}
	return -int(math.Floor(-x + 0.5))
}

// lis3dhSensitivityMG returns the sensitivity in mg/LSB for a mode and a
// full-scale range (datasheet Table 4).
func lis3dhSensitivityMG(mode, rangeG int) float64 {
	switch mode {
	case LIS3DHModeLowPower:
		switch rangeG {
		case LIS3DHRange4G:
			return 32
		case LIS3DHRange8G:
			return 64
		case LIS3DHRange16G:
			return 192
		}
		return 16
	case LIS3DHModeNormal:
		switch rangeG {
		case LIS3DHRange4G:
			return 8
		case LIS3DHRange8G:
			return 16
		case LIS3DHRange16G:
			return 48
		}
		return 4
	default:
		switch rangeG {
		case LIS3DHRange4G:
			return 2
		case LIS3DHRange8G:
			return 4
		case LIS3DHRange16G:
			return 12
		}
		return 1
	}
}

// lis3dhShift returns the right-shift applied to the left-justified 16-bit
// output pair for a mode.
func lis3dhShift(mode int) uint {
	switch mode {
	case LIS3DHModeLowPower:
		return 8
	case LIS3DHModeNormal:
		return 6
	default:
		return 4
	}
}

// lis3dhADCShift returns the right-shift applied to the left-justified
// auxiliary ADC output pair for a mode: 10-bit in normal and
// high-resolution mode, 8-bit in low-power mode.
func lis3dhADCShift(mode int) uint {
	if mode == LIS3DHModeLowPower {
		return 8
	}
	return 6
}

// lis3dhThresholdLSBMG returns the interrupt-threshold LSB in mg for a
// full-scale range (datasheet scale, full-scale / 128).
func lis3dhThresholdLSBMG(rangeG int) float64 {
	switch rangeG {
	case LIS3DHRange4G:
		return 32
	case LIS3DHRange8G:
		return 62
	case LIS3DHRange16G:
		return 186
	}
	return 16
}

// lis3dhSourceBit returns the bit position a source occupies in the
// combined interrupt status.
func lis3dhSourceBit(source uint16) int {
	bit := 0
	for v := source; v > 1; v >>= 1 {
		bit++
	}
	return bit
}

// Sample is one FIFO sample set: the acceleration on the three axes.
type Sample struct {
	// X is the acceleration on the X axis in g.
	X float64
	// Y is the acceleration on the Y axis in g.
	Y float64
	// Z is the acceleration on the Z axis in g.
	Z float64
}

// LIS3DHMinimal is the LIS3DH 3-axis accelerometer — minimal interface.
//
// Reads X, Y, Z acceleration in g with sensible defaults; no configuration
// is required beyond the connection.
//
// The constructor runs the datasheet Initialization Sequence: it verifies
// WHO_AM_I (0x0F) == 0x33, puts the control registers in a known state
// (the chip has no soft reset), then configures ±2 g, 100 Hz ODR,
// high-resolution 12-bit mode with BDU on, and waits 7/ODR (70 ms) for the
// converter to turn on.
//
// Default configuration (baked in at construction):
//   - Range ±2 g (1 mg/LSB)
//   - 100 Hz output data rate
//   - High-resolution (12-bit) mode, all three axes enabled
//   - BDU = 1 (block data update)
//   - High-pass filter bypassed, FIFO bypassed
//   - All interrupt routing cleared
//   - Aux ADC and temperature sensor off
//   - CTRL_REG0 untouched
type LIS3DHMinimal struct {
	conn   connection.RegisterConnection
	addr   uint8
	rangeG int
	mode   int
	rateHz int
}

// NewLIS3DHMinimal creates an LIS3DHMinimal and runs the datasheet
// Initialization Sequence.
//
// conn must be a register-capable connection (I²C) already bound to the
// chip's address; addr is the chip's 7-bit I²C address (0x18 with SA0 =
// GND, 0x19 with SA0 = VDD_IO) and is reported in the identity error.
//
// It returns an error when WHO_AM_I does not read 0x33 — wrong chip, wrong
// address or a wiring problem.
func NewLIS3DHMinimal(conn connection.RegisterConnection, addr uint8) (*LIS3DHMinimal, error) {
	c := &LIS3DHMinimal{
		conn:   conn,
		addr:   addr,
		rangeG: LIS3DHRange2G,
		mode:   LIS3DHModeHighResolution,
		rateHz: 100,
	}
	if err := c.runInitSequence(); err != nil {
		return nil, err
	}
	return c, nil
}

// writeReg writes one byte to a register.
func (c *LIS3DHMinimal) writeReg(reg, value uint8) error {
	return c.conn.WriteReg(uint32(reg), []byte{value})
}

// readReg8 reads one byte from a register.
func (c *LIS3DHMinimal) readReg8(reg uint8) (uint8, error) {
	b, err := c.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	if len(b) < 1 {
		return 0, fmt.Errorf("LIS3DH: short read of register 0x%02X", reg)
	}
	return b[0], nil
}

// readBurst reads n bytes starting at reg. A multi-byte transfer sets the
// MSb of the sub-address so the chip auto-increments the register address.
func (c *LIS3DHMinimal) readBurst(reg uint8, n int) ([]byte, error) {
	sub := reg
	if n > 1 {
		sub |= lis3dhAutoIncrement
	}
	return c.conn.ReadReg(uint32(sub), n)
}

// decodeAxis sign-extends the left-justified 16-bit output pair and
// arithmetic-shifts it right for the current mode.
func (c *LIS3DHMinimal) decodeAxis(low, high uint8) int {
	raw16 := int16(uint16(low) | uint16(high)<<8)
	return int(raw16 >> lis3dhShift(c.mode))
}

// scale converts a raw count into g with the sensitivity of the current
// mode and range.
func (c *LIS3DHMinimal) scale(counts int) float64 {
	return float64(counts) * lis3dhSensitivityMG(c.mode, c.rangeG) / 1000.0
}

// settleDuration returns the time to wait after an ODR or mode change: the
// longer of the mode's turn-on time (datasheet Table 10) and one ODR
// period.
func (c *LIS3DHMinimal) settleDuration() time.Duration {
	if c.rateHz <= 0 {
		return 0
	}
	periodMs := 1000.0 / float64(c.rateHz)
	var turnOnMs float64
	switch c.mode {
	case LIS3DHModeLowPower:
		turnOnMs = 1.0
	case LIS3DHModeNormal:
		turnOnMs = 1.6
	default:
		turnOnMs = 7.0 * periodMs
	}
	return time.Duration(math.Ceil(math.Max(turnOnMs, periodMs))) * time.Millisecond
}

// runInitSequence runs the datasheet Initialization Sequence (steps 2-6).
func (c *LIS3DHMinimal) runInitSequence() error {
	// The power-on boot procedure needs ~5 ms; no register access is
	// allowed before it completed.
	time.Sleep(lis3dhBootDelay)

	// Step 2: verify WHO_AM_I.
	id, err := c.readReg8(lis3dhRegWhoAmI)
	if err != nil {
		return err
	}
	if id != lis3dhWhoAmIValue {
		return fmt.Errorf("LIS3DH WHO_AM_I at 0x%02X: expected 0x%02X, got 0x%02X",
			c.addr, lis3dhWhoAmIValue, id)
	}

	// Step 3: the chip has no soft reset — put the control registers in a
	// known state. CTRL_REG0 and the interrupt-generator registers are
	// left untouched, reserved registers are never written.
	for _, reg := range []uint8{
		lis3dhRegCtrlReg2,
		lis3dhRegCtrlReg3,
		lis3dhRegCtrlReg5,
		lis3dhRegCtrlReg6,
		lis3dhRegFifoCtrl,
		lis3dhRegTempCfg,
	} {
		if err := c.writeReg(reg, 0x00); err != nil {
			return err
		}
	}

	// Step 4: block data update, ±2 g, high-resolution 12-bit mode.
	if err := c.writeReg(lis3dhRegCtrlReg4, lis3dhCtrlReg4Default); err != nil {
		return err
	}

	// Step 5: leave power-down — this starts the conversions.
	if err := c.writeReg(lis3dhRegCtrlReg1, lis3dhCtrlReg1Default); err != nil {
		return err
	}

	// Step 6: wait for the high-resolution turn-on (7/ODR = 70 ms at
	// 100 Hz) — the longer of the turn-on time and one ODR period.
	time.Sleep(c.settleDuration())
	return nil
}

// Read returns the 3-axis linear acceleration as (x, y, z) in g.
//
// It burst-reads the six output bytes (0x28-0x2D) with auto-increment so
// X, Y and Z come from a single sample (BDU = 1), sign-extends the
// left-justified 16-bit pairs and scales them with the sensitivity of the
// current mode and range.
func (c *LIS3DHMinimal) Read() (float64, float64, float64, error) {
	raw, err := c.readBurst(lis3dhRegOutXL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	if len(raw) < 6 {
		return 0, 0, 0, fmt.Errorf("LIS3DH: short read of the output registers")
	}
	return c.scale(c.decodeAxis(raw[0], raw[1])),
		c.scale(c.decodeAxis(raw[2], raw[3])),
		c.scale(c.decodeAxis(raw[4], raw[5])),
		nil
}

// LIS3DHFull is the LIS3DH 3-axis accelerometer — full interface. It
// extends LIS3DHMinimal with range / data-rate / mode selection, raw
// counts, status and temperature reads, the auxiliary ADC, the high-pass
// filter, the 32-level FIFO, both inertial interrupt generators (free-fall,
// 6D / 4D orientation), single / double click detection, the sleep-to-wake
// activity function, interrupt routing and delivery, memory reboot, SDO
// pull-up control and the electrostatic self-test.
type LIS3DHFull struct {
	*LIS3DHMinimal

	enabled1 uint16
	enabled2 uint16

	mu          sync.Mutex
	callback    func(uint16)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewLIS3DHFull creates an LIS3DHFull and runs the datasheet
// Initialization Sequence.
func NewLIS3DHFull(conn connection.RegisterConnection, addr uint8) (*LIS3DHFull, error) {
	m, err := NewLIS3DHMinimal(conn, addr)
	if err != nil {
		return nil, err
	}
	return &LIS3DHFull{LIS3DHMinimal: m}, nil
}

// SetRange sets the full-scale measurement range.
//
// It read-modify-writes the FS field of CTRL_REG4 and updates the cached
// sensitivity used by Read and ReadRaw.
//
// rangeG must be 2, 4, 8 or 16.
func (c *LIS3DHFull) SetRange(rangeG int) error {
	index := -1
	for i, r := range lis3dhRanges {
		if r == rangeG {
			index = i
			break
		}
	}
	if index < 0 {
		return fmt.Errorf("LIS3DH SetRange: range must be one of 2, 4, 8, 16, got %d", rangeG)
	}
	ctrl4, err := c.readReg8(lis3dhRegCtrlReg4)
	if err != nil {
		return err
	}
	if err := c.writeReg(lis3dhRegCtrlReg4, (ctrl4&^0x30)|uint8(index)<<4); err != nil {
		return err
	}
	c.rangeG = rangeG
	return nil
}

// SetDataRate sets the output data rate, or powers the chip down.
//
// It read-modify-writes the ODR field of CTRL_REG1 and waits for the
// turn-on time before returning.
//
// rateHz must be 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620
// or 5376. 1620 Hz and 5376 Hz exist in low-power mode only; 1344 Hz in
// normal / high-resolution mode only.
func (c *LIS3DHFull) SetDataRate(rateHz int) error {
	code, ok := lis3dhRateCodes[rateHz]
	if !ok {
		return fmt.Errorf("LIS3DH SetDataRate: rate must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376, got %d", rateHz)
	}
	for _, r := range lis3dhRateLowPowerOnly {
		if r == rateHz && c.mode != LIS3DHModeLowPower {
			return fmt.Errorf("LIS3DH SetDataRate: rate %d Hz is low-power mode only", rateHz)
		}
	}
	for _, r := range lis3dhRateNonLowPowerOnly {
		if r == rateHz && c.mode == LIS3DHModeLowPower {
			return fmt.Errorf("LIS3DH SetDataRate: rate %d Hz is not available in low-power mode", rateHz)
		}
	}
	ctrl1, err := c.readReg8(lis3dhRegCtrlReg1)
	if err != nil {
		return err
	}
	if err := c.writeReg(lis3dhRegCtrlReg1, (ctrl1&0x0F)|code); err != nil {
		return err
	}
	c.rateHz = rateHz
	time.Sleep(c.settleDuration())
	return nil
}

// SetMode sets the operating mode (the resolution / power trade-off).
//
// It read-modify-writes LPen in CTRL_REG1 and HR in CTRL_REG4, updates the
// cached resolution and waits for the mode's turn-on time.
//
// mode must be LIS3DHModeLowPower (8-bit), LIS3DHModeNormal (10-bit) or
// LIS3DHModeHighResolution (12-bit). A rate that does not exist in the
// requested mode is rejected, so LPen = 1 with HR = 1 is never written.
func (c *LIS3DHFull) SetMode(mode int) error {
	switch mode {
	case LIS3DHModeLowPower, LIS3DHModeNormal, LIS3DHModeHighResolution:
	default:
		return fmt.Errorf("LIS3DH SetMode: mode must be 0 (low-power), 1 (normal) or 2 (high-resolution), got %d", mode)
	}
	for _, r := range lis3dhRateNonLowPowerOnly {
		if r == c.rateHz && mode == LIS3DHModeLowPower {
			return fmt.Errorf("LIS3DH SetMode: rate %d Hz is not available in low-power mode", c.rateHz)
		}
	}
	for _, r := range lis3dhRateLowPowerOnly {
		if r == c.rateHz && mode != LIS3DHModeLowPower {
			return fmt.Errorf("LIS3DH SetMode: rate %d Hz is low-power mode only", c.rateHz)
		}
	}
	ctrl1, err := c.readReg8(lis3dhRegCtrlReg1)
	if err != nil {
		return err
	}
	ctrl4, err := c.readReg8(lis3dhRegCtrlReg4)
	if err != nil {
		return err
	}
	if mode == LIS3DHModeLowPower {
		ctrl1 |= 0x08  // LPen = 1
		ctrl4 &^= 0x08 // HR = 0 (LPen=1 with HR=1 is not allowed)
	} else {
		ctrl1 &^= 0x08 // LPen = 0
		if mode == LIS3DHModeHighResolution {
			ctrl4 |= 0x08 // HR = 1
		} else {
			ctrl4 &^= 0x08 // HR = 0
		}
	}
	if err := c.writeReg(lis3dhRegCtrlReg1, ctrl1); err != nil {
		return err
	}
	if err := c.writeReg(lis3dhRegCtrlReg4, ctrl4); err != nil {
		return err
	}
	c.mode = mode
	time.Sleep(c.settleDuration())
	return nil
}

// SetAxes enables or disables the individual measurement axes.
func (c *LIS3DHFull) SetAxes(x, y, z bool) error {
	ctrl1, err := c.readReg8(lis3dhRegCtrlReg1)
	if err != nil {
		return err
	}
	axes := uint8(0)
	if x {
		axes |= 0x01
	}
	if y {
		axes |= 0x02
	}
	if z {
		axes |= 0x04
	}
	return c.writeReg(lis3dhRegCtrlReg1, (ctrl1&0xF8)|axes)
}

// PowerDown stops the conversions by setting ODR = 0000. The register
// contents are retained.
func (c *LIS3DHFull) PowerDown() error {
	ctrl1, err := c.readReg8(lis3dhRegCtrlReg1)
	if err != nil {
		return err
	}
	if err := c.writeReg(lis3dhRegCtrlReg1, ctrl1&0x0F); err != nil {
		return err
	}
	c.rateHz = 0
	return nil
}

// ReadRaw returns the raw acceleration counts as (x, y, z).
//
// The counts are signed and right-shifted for the current resolution:
// 12-bit in high-resolution mode, 10-bit in normal mode, 8-bit in
// low-power mode.
func (c *LIS3DHFull) ReadRaw() (int, int, int, error) {
	raw, err := c.readBurst(lis3dhRegOutXL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	if len(raw) < 6 {
		return 0, 0, 0, fmt.Errorf("LIS3DH: short read of the output registers")
	}
	return c.decodeAxis(raw[0], raw[1]),
		c.decodeAxis(raw[2], raw[3]),
		c.decodeAxis(raw[4], raw[5]),
		nil
}

// NewDataAvailable reports whether a complete X/Y/Z sample set is ready.
//
// It is STATUS_REG.ZYXDA, set when all three axes have new data.
func (c *LIS3DHFull) NewDataAvailable() (bool, error) {
	status, err := c.readReg8(lis3dhRegStatus)
	if err != nil {
		return false, err
	}
	return status&0x08 != 0, nil
}

// ReadStatus returns the STATUS register byte: the overrun
// (ZYXOR..XOR) and data-available (ZYXDA..XDA) flags.
func (c *LIS3DHFull) ReadStatus() (uint8, error) {
	return c.readReg8(lis3dhRegStatus)
}

// ReadTemperatureDelta returns the relative on-chip temperature in °C.
//
// It enables ADC_EN and TEMP_EN in TEMP_CFG_REG on first use — the
// channel-3 conversions run through the same ADC — and waits one ODR
// period for the first conversion.
//
// The datasheet specifies only 1 digit/°C with no zero-degree reference,
// so the value is a relative delta, never an absolute temperature.
func (c *LIS3DHFull) ReadTemperatureDelta() (int, error) {
	cfg, err := c.readReg8(lis3dhRegTempCfg)
	if err != nil {
		return 0, err
	}
	if cfg&0xC0 != 0xC0 {
		if err := c.writeReg(lis3dhRegTempCfg, cfg|0xC0); err != nil {
			return 0, err
		}
		if c.rateHz > 0 {
			time.Sleep(time.Duration(math.Ceil(1000.0/float64(c.rateHz))) * time.Millisecond)
		}
	}
	raw, err := c.readReg8(lis3dhRegOutADC3H)
	if err != nil {
		return 0, err
	}
	return int(connection.ToSigned(uint32(raw), 8)), nil
}

// ReadADC returns one auxiliary ADC channel input voltage in V.
//
// channel must be 1, 2 or 3. Channel 3 is the temperature-sensor input
// while TEMP_EN = 1 and is rejected in that case.
//
// The mapping is derived from the stated input range (1.200 V ± 400 mV)
// — the datasheet gives no explicit voltage formula.
func (c *LIS3DHFull) ReadADC(channel int) (float64, error) {
	if channel < 1 || channel > 3 {
		return 0, fmt.Errorf("LIS3DH ReadADC: channel must be 1, 2 or 3, got %d", channel)
	}
	if channel == 3 {
		cfg, err := c.readReg8(lis3dhRegTempCfg)
		if err != nil {
			return 0, err
		}
		if cfg&0x40 != 0 {
			return 0, fmt.Errorf("LIS3DH ReadADC: channel 3 is the temperature sensor while TEMP_EN = 1")
		}
	}
	base := lis3dhRegOutADC1L + uint8((channel-1)*2)
	raw, err := c.readBurst(base, 2)
	if err != nil {
		return 0, err
	}
	if len(raw) < 2 {
		return 0, fmt.Errorf("LIS3DH: short read of the ADC registers")
	}
	raw16 := int16(uint16(raw[0]) | uint16(raw[1])<<8)
	counts := int(raw16 >> lis3dhADCShift(c.mode))
	return 1.200 + float64(counts)*(0.400/512.0), nil
}

// EnableADC enables or disables the auxiliary ADC (ADC_EN in TEMP_CFG_REG).
func (c *LIS3DHFull) EnableADC(enabled bool) error {
	cfg, err := c.readReg8(lis3dhRegTempCfg)
	if err != nil {
		return err
	}
	if enabled {
		cfg |= 0x80
	} else {
		cfg &^= 0x80
	}
	return c.writeReg(lis3dhRegTempCfg, cfg)
}

// SetHighPass configures the high-pass filter (CTRL_REG2).
//
// mode is the HPM field: 0 normal (reset by reading REFERENCE), 1
// reference signal for filtering, 2 normal, 3 auto-reset on interrupt
// event. cutoff is the HPCF<2:1> code 0-3 — the datasheet tabulates no Hz
// values, they scale with the ODR.
//
// filterOutput routes the filtered data to the output registers and the
// FIFO (FDS); onIA1, onIA2 and onClick apply the filter to interrupt
// generator 1, generator 2 and the click function.
func (c *LIS3DHFull) SetHighPass(mode, cutoff int, filterOutput, onIA1, onIA2, onClick bool) error {
	if mode < 0 || mode > 3 {
		return fmt.Errorf("LIS3DH SetHighPass: mode must be 0-3, got %d", mode)
	}
	if cutoff < 0 || cutoff > 3 {
		return fmt.Errorf("LIS3DH SetHighPass: cutoff must be 0-3, got %d", cutoff)
	}
	value := uint8(mode)<<6 | uint8(cutoff)<<4
	if filterOutput {
		value |= 0x08
	}
	if onClick {
		value |= 0x04
	}
	if onIA2 {
		value |= 0x02
	}
	if onIA1 {
		value |= 0x01
	}
	return c.writeReg(lis3dhRegCtrlReg2, value)
}

// ResetHighPass resets the filter by reading REFERENCE (HPM = 00) and
// returns the value that was read.
func (c *LIS3DHFull) ResetHighPass() (uint8, error) {
	return c.readReg8(lis3dhRegReference)
}

// SetReference writes the high-pass reference value (HPM = 01).
//
// value must be 0-255.
func (c *LIS3DHFull) SetReference(value int) error {
	if value < 0 || value > 255 {
		return fmt.Errorf("LIS3DH SetReference: value must be 0-255, got %d", value)
	}
	return c.writeReg(lis3dhRegReference, uint8(value))
}

// SetFIFO configures the 32-level FIFO (FIFO_CTRL_REG).
//
// mode is 0 bypass, 1 FIFO, 2 stream or 3 stream-to-FIFO; watermark is the
// FTH level 0-31; triggerInt2 selects INT2 instead of INT1 as the
// stream-to-FIFO trigger.
//
// It sets FIFO_EN in CTRL_REG5 for modes 1-3; returning to bypass clears
// the buffer. Only bypass mode empties the buffer — restart a FIFO mode
// through bypass after a full drain.
func (c *LIS3DHFull) SetFIFO(mode, watermark int, triggerInt2 bool) error {
	if mode < 0 || mode > 3 {
		return fmt.Errorf("LIS3DH SetFIFO: mode must be 0 (bypass), 1 (FIFO), 2 (stream) or 3 (stream-to-FIFO), got %d", mode)
	}
	if watermark < 0 || watermark > 31 {
		return fmt.Errorf("LIS3DH SetFIFO: watermark must be 0-31, got %d", watermark)
	}
	value := uint8(mode) << 6
	if triggerInt2 {
		value |= 0x20
	}
	value |= uint8(watermark)
	if err := c.writeReg(lis3dhRegFifoCtrl, value); err != nil {
		return err
	}
	ctrl5, err := c.readReg8(lis3dhRegCtrlReg5)
	if err != nil {
		return err
	}
	if mode == 0 {
		ctrl5 &^= 0x40
	} else {
		ctrl5 |= 0x40
	}
	return c.writeReg(lis3dhRegCtrlReg5, ctrl5)
}

// FIFOLevel returns the number of unread sample sets in the FIFO
// (FSS<4:0>, 0-32).
func (c *LIS3DHFull) FIFOLevel() (int, error) {
	raw, err := c.readReg8(lis3dhRegFifoSrc)
	if err != nil {
		return 0, err
	}
	return int(raw & 0x1F), nil
}

// ReadFIFO drains sample sets from the FIFO.
//
// Each sample set is one 6-byte burst of 0x28-0x2D; the address
// auto-increment wraps back to 0x28, so a full drain is 32 reads.
//
// count is the maximum number of sample sets to read; a count of 0 or less
// reads every sample the FIFO currently holds. It never reads more than
// the FIFO actually holds.
func (c *LIS3DHFull) ReadFIFO(count int) ([]Sample, error) {
	level, err := c.FIFOLevel()
	if err != nil {
		return nil, err
	}
	n := level
	if count > 0 && count < n {
		n = count
	}
	samples := make([]Sample, 0, n)
	for i := 0; i < n; i++ {
		raw, err := c.readBurst(lis3dhRegOutXL, 6)
		if err != nil {
			return nil, err
		}
		if len(raw) < 6 {
			return nil, fmt.Errorf("LIS3DH: short read of the output registers")
		}
		samples = append(samples, Sample{
			X: c.scale(c.decodeAxis(raw[0], raw[1])),
			Y: c.scale(c.decodeAxis(raw[2], raw[3])),
			Z: c.scale(c.decodeAxis(raw[4], raw[5])),
		})
	}
	return samples, nil
}

// ReadFIFOStatus decodes FIFO_SRC_REG and returns the watermark, overrun
// and empty flags plus the number of unread sample sets.
func (c *LIS3DHFull) ReadFIFOStatus() (bool, bool, bool, int, error) {
	raw, err := c.readReg8(lis3dhRegFifoSrc)
	if err != nil {
		return false, false, false, 0, err
	}
	return raw&0x80 != 0, raw&0x40 != 0, raw&0x20 != 0, int(raw & 0x1F), nil
}

// SetMotionInterrupt configures an inertial interrupt generator.
//
// generator is 1 or 2 — the INT1 or the INT2 register block. events is the
// 6-bit ZHIE..XLIE event-enable mask (0-0x3F). thresholdG is the
// threshold in g, scaled with the current full-scale range (16 / 32 / 62 /
// 186 mg per LSb). durationMs is the minimum time the events must hold
// (INTx_DURATION, 1 LSb = 1/ODR). andMode selects the AND combination of
// the enabled events (AOI = 1) instead of OR.
func (c *LIS3DHFull) SetMotionInterrupt(generator, events int, thresholdG, durationMs float64, andMode bool) error {
	cfgReg, thsReg, durReg, err := c.generatorRegs(generator)
	if err != nil {
		return err
	}
	if events < 0 || events > 0x3F {
		return fmt.Errorf("LIS3DH SetMotionInterrupt: events must be a 6-bit mask (0-0x3F), got %d", events)
	}
	cfg := uint8(events & 0x3F)
	if andMode {
		cfg |= 0x80
	}
	if err := c.writeReg(cfgReg, cfg); err != nil {
		return err
	}
	if err := c.writeReg(thsReg, c.thresholdCode(thresholdG)); err != nil {
		return err
	}
	return c.writeReg(durReg, c.durationCode(durationMs, 127))
}

// SetFreeFall configures the free-fall detector: all three axes low, AND
// mode (INTx_CFG = 0x95 — AOI plus XLIE / YLIE / ZLIE).
//
// generator is 1 or 2, thresholdG the free-fall threshold in g and
// durationMs the minimum time below the threshold.
func (c *LIS3DHFull) SetFreeFall(generator int, thresholdG, durationMs float64) error {
	cfgReg, thsReg, durReg, err := c.generatorRegs(generator)
	if err != nil {
		return err
	}
	if err := c.writeReg(cfgReg, 0x95); err != nil {
		return err
	}
	if err := c.writeReg(thsReg, c.thresholdCode(thresholdG)); err != nil {
		return err
	}
	return c.writeReg(durReg, c.durationCode(durationMs, 127))
}

// SetOrientation configures 6D / 4D orientation detection.
//
// AOI / 6D = 01 is movement recognition (a one-ODR pulse), 11 is position
// recognition (a level, active while the orientation stays inside a known
// zone). fourD enables 4D detection (D4D_INTx), which disables the Z-axis
// zones.
//
// generator is 1 or 2.
func (c *LIS3DHFull) SetOrientation(generator int, position, fourD bool) error {
	cfgReg, _, _, err := c.generatorRegs(generator)
	if err != nil {
		return err
	}
	axisZones := uint8(0x3F)
	if fourD {
		axisZones = 0x0F
	}
	cfg := uint8(0x40)
	if position {
		cfg = 0xC0
	}
	cfg |= axisZones
	if err := c.writeReg(cfgReg, cfg); err != nil {
		return err
	}
	ctrl5, err := c.readReg8(lis3dhRegCtrlReg5)
	if err != nil {
		return err
	}
	d4dBit := uint8(0x04)
	if generator == 2 {
		d4dBit = 0x01
	}
	if fourD {
		ctrl5 |= d4dBit
	} else {
		ctrl5 &^= d4dBit
	}
	return c.writeReg(lis3dhRegCtrlReg5, ctrl5)
}

// SetLatch latches a generator's interrupt until its INTx_SRC is read.
//
// generator is 1 or 2.
func (c *LIS3DHFull) SetLatch(generator int, enabled bool) error {
	if generator != 1 && generator != 2 {
		return fmt.Errorf("LIS3DH SetLatch: generator must be 1 or 2, got %d", generator)
	}
	bit := uint8(0x08)
	if generator == 2 {
		bit = 0x02
	}
	ctrl5, err := c.readReg8(lis3dhRegCtrlReg5)
	if err != nil {
		return err
	}
	if enabled {
		ctrl5 |= bit
	} else {
		ctrl5 &^= bit
	}
	return c.writeReg(lis3dhRegCtrlReg5, ctrl5)
}

// ReadInterruptSource returns a generator's INTx_SRC byte — reading it
// **clears** the interrupt.
//
// generator is 1 or 2. IA is bit 6, the per-axis event flags are in bits
// 5-0.
func (c *LIS3DHFull) ReadInterruptSource(generator int) (uint8, error) {
	if generator != 1 && generator != 2 {
		return 0, fmt.Errorf("LIS3DH ReadInterruptSource: generator must be 1 or 2, got %d", generator)
	}
	if generator == 1 {
		return c.readReg8(lis3dhRegInt1Src)
	}
	return c.readReg8(lis3dhRegInt2Src)
}

// SetClick configures single / double click detection.
//
// single and double are axis masks (X = 1, Y = 2, Z = 4). thresholdG is
// the click threshold in g, on the same scale as INTx_THS. timeLimitMs is
// the maximum time between the two clicks of a double click, latencyMs the
// minimum time between two consecutive clicks and windowMs the maximum
// time after the first click of a double click. latch keeps the interrupt
// high until CLICK_SRC is read (LIR_Click).
func (c *LIS3DHFull) SetClick(single, double int, thresholdG, timeLimitMs, latencyMs, windowMs float64, latch bool) error {
	if single < 0 || single > 7 || double < 0 || double > 7 {
		return fmt.Errorf("LIS3DH SetClick: single / double must be axis masks (0-7: X=1, Y=2, Z=4)")
	}
	cfg := uint8(single&0x01) | uint8(single&0x02)<<1 | uint8(single&0x04)<<2 |
		uint8(double&0x01)<<1 | uint8(double&0x02)<<2 | uint8(double&0x04)<<3
	ths := c.thresholdCode(thresholdG)
	if latch {
		ths |= 0x80
	}
	if err := c.writeReg(lis3dhRegClickCfg, cfg); err != nil {
		return err
	}
	if err := c.writeReg(lis3dhRegClickThs, ths); err != nil {
		return err
	}
	if err := c.writeReg(lis3dhRegTimeLimit, c.durationCode(timeLimitMs, 127)); err != nil {
		return err
	}
	if err := c.writeReg(lis3dhRegTimeLatency, c.durationCode(latencyMs, 255)); err != nil {
		return err
	}
	return c.writeReg(lis3dhRegTimeWindow, c.durationCode(windowMs, 255))
}

// ReadClickSource returns the CLICK_SRC byte — reading it **clears** the
// click interrupt.
//
// IA is bit 6, DCLICK bit 5, SCLICK bit 4, Sign bit 3 and the axis flags
// are in bits 2-0.
func (c *LIS3DHFull) ReadClickSource() (uint8, error) {
	return c.readReg8(lis3dhRegClickSrc)
}

// SetActivity configures the sleep-to-wake / return-to-sleep activity
// function.
//
// thresholdG is the activation threshold in g (ACT_THS) and durationMs the
// activation duration (ACT_DUR, where 1 LSb = (8*ACT_DUR + 1)/ODR).
//
// While the function has dropped the chip into its 10 Hz low-power state
// the ODR, LPen and HR settings are ignored; the chip restores them on
// activity. Pair it with SourceActivity.
func (c *LIS3DHFull) SetActivity(thresholdG, durationMs float64) error {
	code := c.thresholdCode(thresholdG)
	if code == 0 {
		return fmt.Errorf("LIS3DH SetActivity: threshold is below 1 LSb — ACT_THS must be non-zero")
	}
	dur := lis3dhRound((durationMs*float64(c.rateHz)/1000.0 - 1) / 8.0)
	if dur < 0 {
		dur = 0
	}
	if dur > 255 {
		dur = 255
	}
	if err := c.writeReg(lis3dhRegActThs, code); err != nil {
		return err
	}
	return c.writeReg(lis3dhRegActDur, uint8(dur))
}

// SetInterruptPolarity sets the shared INT1 / INT2 output polarity
// (INT_POLARITY).
//
// activeLow selects active-low outputs; the reset default is active-high.
func (c *LIS3DHFull) SetInterruptPolarity(activeLow bool) error {
	ctrl6, err := c.readReg8(lis3dhRegCtrlReg6)
	if err != nil {
		return err
	}
	if activeLow {
		ctrl6 |= 0x02
	} else {
		ctrl6 &^= 0x02
	}
	return c.writeReg(lis3dhRegCtrlReg6, ctrl6)
}

// EnableInterrupt routes one interrupt source to INT1 or INT2.
//
// It only routes the source — the generator or the function behind it is
// configured with the Set* methods. pin is ignored for the INT1-only
// (data-ready, aux data-ready, watermark, overrun) and the INT2-only
// (activity, boot) sources.
//
// source is one of the Source* constants and pin is 1 or 2.
func (c *LIS3DHFull) EnableInterrupt(source uint16, pin int) error {
	reg, mask, track, err := c.route(source, pin)
	if err != nil {
		return err
	}
	value, err := c.readReg8(reg)
	if err != nil {
		return err
	}
	if err := c.writeReg(reg, value|mask); err != nil {
		return err
	}
	c.setEnabled(track, source, true)
	return nil
}

// DisableInterrupt removes one interrupt source from INT1 or INT2.
//
// source is one of the Source* constants and pin is 1 or 2.
func (c *LIS3DHFull) DisableInterrupt(source uint16, pin int) error {
	reg, mask, track, err := c.route(source, pin)
	if err != nil {
		return err
	}
	value, err := c.readReg8(reg)
	if err != nil {
		return err
	}
	if err := c.writeReg(reg, value&^mask); err != nil {
		return err
	}
	c.setEnabled(track, source, false)
	return nil
}

// OnInterrupt subscribes callback to the chip's INT line.
//
// The callback receives the combined status returned by PollInterrupt and
// is only called for a non-zero status. The delivery uses the connection's
// IntPin when one is wired and falls back to a 5 ms polling goroutine
// otherwise.
func (c *LIS3DHFull) OnInterrupt(callback func(uint16)) error {
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

	pin := c.conn.IntPin()
	if pin == nil {
		poll := connection.NewDefaultPollingInputPin()
		c.mu.Lock()
		c.pollPin = poll
		c.mu.Unlock()
		pin = poll
	}
	unsub := pin.OnEdge(connection.Falling, func() { c.handleEdge() })
	c.mu.Lock()
	c.unsubscribe = unsub
	c.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops the interrupt delivery.
func (c *LIS3DHFull) OffInterrupt() error {
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

// PollInterrupt reads the source registers of every currently enabled
// source and returns the combined status.
//
// Reading INT1_SRC / INT2_SRC / CLICK_SRC **clears** their latched flags
// and the INT pin. The status bits are SourceDataReady (bit 0),
// SourceAuxDataReady (bit 1), SourceWatermark (bit 2), SourceOverrun (bit
// 3), SourceIA1 (bit 4), SourceIA2 (bit 5) and SourceClick (bit 6).
// Sources that are not enabled read as 0; SourceActivity and SourceBoot
// have no register flag and are never reported.
func (c *LIS3DHFull) PollInterrupt() (uint16, error) {
	e1 := c.enabled1
	e2 := c.enabled2
	enabled := e1 | e2
	var status uint16

	// The INT1-only sources are routed through CTRL_REG3.
	if e1&(1<<lis3dhSourceBit(SourceDataReady)) != 0 {
		raw, err := c.readReg8(lis3dhRegStatus)
		if err != nil {
			return 0, err
		}
		if raw&0x08 != 0 {
			status |= SourceDataReady
		}
	}
	if e1&(1<<lis3dhSourceBit(SourceAuxDataReady)) != 0 {
		raw, err := c.readReg8(lis3dhRegStatusAux)
		if err != nil {
			return 0, err
		}
		if raw&0x08 != 0 {
			status |= SourceAuxDataReady
		}
	}
	if e1&(1<<lis3dhSourceBit(SourceWatermark)) != 0 || e1&(1<<lis3dhSourceBit(SourceOverrun)) != 0 {
		raw, err := c.readReg8(lis3dhRegFifoSrc)
		if err != nil {
			return 0, err
		}
		if e1&(1<<lis3dhSourceBit(SourceWatermark)) != 0 && raw&0x80 != 0 {
			status |= SourceWatermark
		}
		if e1&(1<<lis3dhSourceBit(SourceOverrun)) != 0 && raw&0x40 != 0 {
			status |= SourceOverrun
		}
	}
	// The generator flags live in their own source register, no matter
	// which pin the generator is routed to.
	if enabled&(1<<lis3dhSourceBit(SourceIA1)) != 0 {
		raw, err := c.readReg8(lis3dhRegInt1Src)
		if err != nil {
			return 0, err
		}
		if raw&0x40 != 0 {
			status |= SourceIA1
		}
	}
	if enabled&(1<<lis3dhSourceBit(SourceIA2)) != 0 {
		raw, err := c.readReg8(lis3dhRegInt2Src)
		if err != nil {
			return 0, err
		}
		if raw&0x40 != 0 {
			status |= SourceIA2
		}
	}
	if enabled&(1<<lis3dhSourceBit(SourceClick)) != 0 {
		raw, err := c.readReg8(lis3dhRegClickSrc)
		if err != nil {
			return 0, err
		}
		if raw&0x40 != 0 {
			status |= SourceClick
		}
	}
	return status, nil
}

// Reboot reboots the memory content (BOOT = 1) and re-runs the
// Initialization Sequence.
//
// It reloads the factory trim and waits 5 ms. The datasheet does not say
// whether the control registers survive, so the Initialization Sequence is
// re-run afterwards and the configuration returns to the Minimal defaults.
func (c *LIS3DHFull) Reboot() error {
	ctrl5, err := c.readReg8(lis3dhRegCtrlReg5)
	if err != nil {
		return err
	}
	if err := c.writeReg(lis3dhRegCtrlReg5, ctrl5|0x80); err != nil {
		return err
	}
	time.Sleep(lis3dhBootDelay)
	c.rangeG = LIS3DHRange2G
	c.mode = LIS3DHModeHighResolution
	c.rateHz = 100
	c.enabled1 = 0
	c.enabled2 = 0
	return c.runInitSequence()
}

// SetSDOPullup connects or disconnects the pull-up on SDO/SA0
// (SDO_PU_DISC in CTRL_REG0).
//
// CTRL_REG0 is not a normal register: bit 4 must stay 1 and the other bits
// except SDO_PU_DISC must stay 0, so the register is written with the
// forced values rather than read-modify-written.
func (c *LIS3DHFull) SetSDOPullup(connected bool) error {
	value := uint8(0x10)
	if !connected {
		value |= 0x80
	}
	return c.writeReg(lis3dhRegCtrlReg0, value)
}

// SelfTest runs the datasheet electrostatic self-test.
//
// It configures ±2 g, normal (10-bit) mode, 50 Hz with BDU on, averages a
// few samples with ST = 00, enables ST (01 = self-test 0, 10 = self-test
// 1), waits 2 samples and averages again. Every axis must show a 17-360
// LSb change (1 LSb = 4 mg at 10-bit / ±2 g). The previous CTRL_REG1 /
// CTRL_REG4 configuration is restored afterwards.
//
// mode is 1 (self-test 0) or 2 (self-test 1).
func (c *LIS3DHFull) SelfTest(mode int) (bool, error) {
	if mode != 1 && mode != 2 {
		return false, fmt.Errorf("LIS3DH SelfTest: mode must be 1 (self-test 0) or 2 (self-test 1), got %d", mode)
	}
	savedCtrl1, err := c.readReg8(lis3dhRegCtrlReg1)
	if err != nil {
		return false, err
	}
	savedCtrl4, err := c.readReg8(lis3dhRegCtrlReg4)
	if err != nil {
		return false, err
	}
	savedRange, savedMode, savedRate := c.rangeG, c.mode, c.rateHz
	defer func() {
		_ = c.writeReg(lis3dhRegCtrlReg1, savedCtrl1)
		_ = c.writeReg(lis3dhRegCtrlReg4, savedCtrl4)
		c.rangeG, c.mode, c.rateHz = savedRange, savedMode, savedRate
	}()

	configure := func(stBits uint8) error {
		if err := c.writeReg(lis3dhRegCtrlReg4, 0x80|stBits); err != nil {
			return err
		}
		if err := c.writeReg(lis3dhRegCtrlReg1, 0x47); err != nil {
			return err
		}
		c.rangeG, c.mode, c.rateHz = LIS3DHRange2G, LIS3DHModeNormal, 50
		return nil
	}

	if err := configure(0x00); err != nil {
		return false, err
	}
	time.Sleep(10 * time.Millisecond)
	before, err := c.averageRaw(5, 20*time.Millisecond)
	if err != nil {
		return false, err
	}
	stBits := uint8(0x02)
	if mode == 2 {
		stBits = 0x04
	}
	if err := configure(stBits); err != nil {
		return false, err
	}
	time.Sleep(40 * time.Millisecond) // 2 samples at 50 Hz
	after, err := c.averageRaw(5, 20*time.Millisecond)
	if err != nil {
		return false, err
	}
	for i := 0; i < 3; i++ {
		delta := after[i] - before[i]
		if delta < 0 {
			delta = -delta
		}
		if delta < 17 || delta > 360 {
			return false, nil
		}
	}
	return true, nil
}

// averageRaw reads samples samples, waiting delay between them, and
// returns the averaged raw counts.
func (c *LIS3DHFull) averageRaw(samples int, delay time.Duration) ([3]float64, error) {
	var total [3]float64
	for i := 0; i < samples; i++ {
		x, y, z, err := c.ReadRaw()
		if err != nil {
			return total, err
		}
		total[0] += float64(x)
		total[1] += float64(y)
		total[2] += float64(z)
		time.Sleep(delay)
	}
	return [3]float64{
		total[0] / float64(samples),
		total[1] / float64(samples),
		total[2] / float64(samples),
	}, nil
}

// generatorRegs returns the CFG, THS and DURATION registers of an
// interrupt generator.
func (c *LIS3DHFull) generatorRegs(generator int) (uint8, uint8, uint8, error) {
	switch generator {
	case 1:
		return lis3dhRegInt1Cfg, lis3dhRegInt1Ths, lis3dhRegInt1Dur, nil
	case 2:
		return lis3dhRegInt2Cfg, lis3dhRegInt2Ths, lis3dhRegInt2Dur, nil
	}
	return 0, 0, 0, fmt.Errorf("LIS3DH: generator must be 1 or 2, got %d", generator)
}

// thresholdCode converts a threshold in g into the 7-bit INTx_THS /
// ACT_THS / CLICK_THS code of the current full-scale range.
func (c *LIS3DHFull) thresholdCode(thresholdG float64) uint8 {
	code := lis3dhRound(thresholdG * 1000.0 / lis3dhThresholdLSBMG(c.rangeG))
	if code < 0 {
		code = 0
	}
	if code > 127 {
		code = 127
	}
	return uint8(code)
}

// durationCode converts a duration in ms into the INTx_DURATION /
// TIME_* code of the current output data rate (1 LSb = 1/ODR).
//
// While the chip is powered down there is no ODR to convert with, so the
// code is 0.
func (c *LIS3DHFull) durationCode(durationMs float64, limit int) uint8 {
	if c.rateHz <= 0 {
		return 0
	}
	code := lis3dhRound(durationMs * float64(c.rateHz) / 1000.0)
	if code < 0 {
		code = 0
	}
	if code > limit {
		code = limit
	}
	return uint8(code)
}

// route returns the routing register, the bit mask of the source in it
// and the pin the source is tracked on (1 or 2).
func (c *LIS3DHFull) route(source uint16, pin int) (uint8, uint8, int, error) {
	if pin != 1 && pin != 2 {
		return 0, 0, 0, fmt.Errorf("LIS3DH: pin must be 1 or 2, got %d", pin)
	}
	switch source {
	case SourceDataReady:
		return lis3dhRegCtrlReg3, 0x10, 1, nil
	case SourceAuxDataReady:
		return lis3dhRegCtrlReg3, 0x08, 1, nil
	case SourceWatermark:
		return lis3dhRegCtrlReg3, 0x04, 1, nil
	case SourceOverrun:
		return lis3dhRegCtrlReg3, 0x02, 1, nil
	case SourceIA1:
		if pin == 1 {
			return lis3dhRegCtrlReg3, 0x40, 1, nil
		}
		return lis3dhRegCtrlReg6, 0x40, 2, nil
	case SourceIA2:
		if pin == 1 {
			return lis3dhRegCtrlReg3, 0x20, 1, nil
		}
		return lis3dhRegCtrlReg6, 0x20, 2, nil
	case SourceClick:
		if pin == 1 {
			return lis3dhRegCtrlReg3, 0x80, 1, nil
		}
		return lis3dhRegCtrlReg6, 0x80, 2, nil
	case SourceActivity:
		return lis3dhRegCtrlReg6, 0x08, 2, nil
	case SourceBoot:
		return lis3dhRegCtrlReg6, 0x10, 2, nil
	}
	return 0, 0, 0, fmt.Errorf("LIS3DH: unknown interrupt source 0x%04X", source)
}

// setEnabled records or forgets that a source is routed to a pin.
func (c *LIS3DHFull) setEnabled(track int, source uint16, enabled bool) {
	bit := uint16(1) << lis3dhSourceBit(source)
	if track == 1 {
		if enabled {
			c.enabled1 |= bit
		} else {
			c.enabled1 &^= bit
		}
		return
	}
	if enabled {
		c.enabled2 |= bit
	} else {
		c.enabled2 &^= bit
	}
}

// handleEdge is called on each INT edge (real or polled). It reads and
// clears the interrupt, then dispatches the callback.
func (c *LIS3DHFull) handleEdge() {
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

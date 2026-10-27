// Package accelerometer contains drivers for standalone accelerometers.
package accelerometer

import (
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LSM303 accelerometer register addresses.
const (
	lsm303RegCtrlReg1    uint8 = 0x20
	lsm303RegCtrlReg2    uint8 = 0x21
	lsm303RegCtrlReg3    uint8 = 0x22
	lsm303RegCtrlReg4    uint8 = 0x23
	lsm303RegCtrlReg5    uint8 = 0x24
	lsm303RegCtrlReg6    uint8 = 0x25
	lsm303RegReference   uint8 = 0x26
	lsm303RegStatus      uint8 = 0x27
	lsm303RegOutXL       uint8 = 0x28
	lsm303RegOutXH       uint8 = 0x29
	lsm303RegOutYL       uint8 = 0x2A
	lsm303RegOutYH       uint8 = 0x2B
	lsm303RegOutZL       uint8 = 0x2C
	lsm303RegOutZH       uint8 = 0x2D
	lsm303RegFifoCtrl    uint8 = 0x2E
	lsm303RegFifoSrc     uint8 = 0x2F
	lsm303RegInt1Cfg     uint8 = 0x30
	lsm303RegInt1Src     uint8 = 0x31
	lsm303RegInt1Ths     uint8 = 0x32
	lsm303RegInt1Dur     uint8 = 0x33
	lsm303RegInt2Cfg     uint8 = 0x34
	lsm303RegInt2Src     uint8 = 0x35
	lsm303RegInt2Ths     uint8 = 0x36
	lsm303RegInt2Dur     uint8 = 0x37
	lsm303RegClickCfg    uint8 = 0x38
	lsm303RegClickSrc    uint8 = 0x39
	lsm303RegClickThs    uint8 = 0x3A
	lsm303RegTimeLimit   uint8 = 0x3B
	lsm303RegTimeLatency uint8 = 0x3C
	lsm303RegTimeWindow  uint8 = 0x3D
)

// LSM303 magnetometer register addresses.
const (
	lsm303MagRegCRA   uint8 = 0x00
	lsm303MagRegCRB   uint8 = 0x01
	lsm303MagRegMR    uint8 = 0x02
	lsm303MagRegOutXH uint8 = 0x03
	lsm303MagRegOutSR uint8 = 0x09
	lsm303MagRegIRA   uint8 = 0x0A
	lsm303MagRegTempH uint8 = 0x31
	lsm303MagRegTempL uint8 = 0x32
)

const (
	lsm303CtrlReg4Default uint8 = 0x88
	lsm303CtrlReg1Default uint8 = 0x57
	lsm303AutoIncrement   uint8 = 0x80
	lsm303BootDelay             = 5 * time.Millisecond
)

// LSM303 operating modes.
const (
	// LSM303ModeLowPower selects the 8-bit low-power mode.
	LSM303ModeLowPower = 0
	// LSM303ModeNormal selects the 10-bit normal mode.
	LSM303ModeNormal = 1
	// LSM303ModeHighResolution selects the 12-bit high-resolution mode.
	LSM303ModeHighResolution = 2
)

// LSM303 full-scale accelerometer ranges in g.
const (
	LSM303Range2G  = 2
	LSM303Range4G  = 4
	LSM303Range8G  = 8
	LSM303Range16G = 16
)

// LSM303 FIFO modes.
const (
	LSM303FIFOBypass       = 0
	LSM303FIFOFIFO         = 1
	LSM303FIFOStream       = 2
	LSM303FIFOStreamToFIFO = 3
)

// LSM303 interrupt sources. The value is the bit position in the combined
// status returned by PollInterrupt. SourceBoot is pin-only and never
// reported; SourceMagDataReady is on the dedicated DRDY pin and cannot be
// routed.
const (
	SourceDataReady    uint16 = 0x0001
	SourceWatermark    uint16 = 0x0002
	SourceOverrun      uint16 = 0x0004
	SourceIA1          uint16 = 0x0008
	SourceIA2          uint16 = 0x0010
	SourceClick        uint16 = 0x0020
	SourceBoot         uint16 = 0x0040
	SourceMagDataReady uint16 = 0x0080
)

var lsm303Ranges = []int{LSM303Range2G, LSM303Range4G, LSM303Range8G, LSM303Range16G}

var lsm303RateCodes = map[int]uint8{
	0: 0x00, 1: 0x10, 10: 0x20, 25: 0x30, 50: 0x40, 100: 0x50,
	200: 0x60, 400: 0x70, 1620: 0x80, 1344: 0x90, 5376: 0x90,
}

var lsm303RateLowPowerOnly = map[int]bool{1620: true, 5376: true}
var lsm303RateNonLowPowerOnly = map[int]bool{1344: true}

type lsm303MagGain struct {
	xy, z   float64
	rangeUT int
}

var lsm303MagGains = map[uint8]lsm303MagGain{
	0x20: {1100, 980, 130},
	0x40: {855, 760, 190},
	0x60: {670, 600, 250},
	0x80: {450, 400, 400},
	0xA0: {400, 355, 470},
	0xC0: {330, 295, 560},
	0xE0: {230, 205, 810},
}

func lsm303Round(x float64) int {
	if x >= 0 {
		return int(math.Floor(x + 0.5))
	}
	return -int(math.Floor(-x + 0.5))
}

func lsm303SensitivityMG(mode, rangeG int) float64 {
	index := 0
	switch rangeG {
	case LSM303Range4G:
		index = 1
	case LSM303Range8G:
		index = 2
	case LSM303Range16G:
		index = 3
	}
	switch mode {
	case LSM303ModeLowPower:
		return []float64{16, 32, 64, 192}[index]
	case LSM303ModeNormal:
		return []float64{4, 8, 16, 48}[index]
	default:
		return []float64{1, 2, 4, 12}[index]
	}
}

func lsm303Shift(mode int) uint {
	switch mode {
	case LSM303ModeLowPower:
		return 8
	case LSM303ModeNormal:
		return 6
	default:
		return 4
	}
}

func lsm303ToSigned16(v uint16) int16 { return int16(v) }

func lsm303MagCodeForRange(rangeUT int) (uint8, bool) {
	for code, gain := range lsm303MagGains {
		if gain.rangeUT == rangeUT {
			return code, true
		}
	}
	return 0, false
}

func lsm303MagRateCode(rateHz float64) (uint8, bool) {
	rates := []struct {
		hz   float64
		code uint8
	}{
		{0.75, 0x00}, {1.5, 0x04}, {3, 0x08}, {7.5, 0x0C},
		{15, 0x10}, {30, 0x14}, {75, 0x18}, {220, 0x1C},
	}
	for _, r := range rates {
		if math.Abs(rateHz-r.hz) < 1e-3 {
			return r.code, true
		}
	}
	return 0, false
}

// LSM303Minimal reads acceleration in g and magnetic flux density in µT with
// sensible defaults baked in.
type LSM303Minimal struct {
	accelConn connection.RegisterConnection
	magConn   connection.RegisterConnection
	rangeG    int
	mode      int
	rateHz    int
	magCode   uint8
	magRateHz float64
}

// NewLSM303Minimal constructs the driver and runs the initialization
// sequence. accelConn is bound to 0x19 and magConn to 0x1E on the same bus.
func NewLSM303Minimal(accelConn, magConn connection.RegisterConnection) (*LSM303Minimal, error) {
	c := &LSM303Minimal{
		accelConn: accelConn,
		magConn:   magConn,
		rangeG:    LSM303Range2G,
		mode:      LSM303ModeHighResolution,
		rateHz:    100,
		magCode:   0x20,
		magRateHz: 15,
	}
	if err := c.runInitSequence(); err != nil {
		return nil, err
	}
	return c, nil
}

func (c *LSM303Minimal) runInitSequence() error {
	time.Sleep(lsm303BootDelay)
	identity, err := c.magReadBurst(lsm303MagRegIRA, 3)
	if err != nil {
		return err
	}
	if identity[0] != 0x48 || identity[1] != 0x34 || identity[2] != 0x33 {
		return &LSM303IdentityError{Got: identity}
	}
	for _, reg := range []uint8{
		lsm303RegCtrlReg2, lsm303RegCtrlReg3, lsm303RegCtrlReg5,
		lsm303RegCtrlReg6, lsm303RegFifoCtrl,
	} {
		if err := c.writeReg(reg, 0x00); err != nil {
			return err
		}
	}
	if err := c.writeReg(lsm303RegCtrlReg4, lsm303CtrlReg4Default); err != nil {
		return err
	}
	if err := c.writeReg(lsm303RegCtrlReg1, lsm303CtrlReg1Default); err != nil {
		return err
	}
	if err := c.magWriteReg(lsm303MagRegCRA, 0x10); err != nil {
		return err
	}
	if err := c.magWriteReg(lsm303MagRegCRB, c.magCode); err != nil {
		return err
	}
	if err := c.magWriteReg(lsm303MagRegMR, 0x00); err != nil {
		return err
	}
	settle := c.settleMS()
	magSettle := int(math.Ceil(1000.0 / c.magRateHz))
	if magSettle > settle {
		settle = magSettle
	}
	time.Sleep(time.Duration(settle) * time.Millisecond)
	return nil
}

// LSM303IdentityError reports a magnetometer identity mismatch.
type LSM303IdentityError struct {
	Got []byte
}

func (e *LSM303IdentityError) Error() string {
	return "LSM303 magnetometer identity: expected 48 34 33"
}

func (c *LSM303Minimal) writeReg(reg, value uint8) error {
	return c.accelConn.WriteReg(uint32(reg), []byte{value})
}

func (c *LSM303Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := c.accelConn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (c *LSM303Minimal) readBurst(reg uint8, n int) ([]byte, error) {
	sub := reg
	if n > 1 {
		sub |= lsm303AutoIncrement
	}
	return c.accelConn.ReadReg(uint32(sub), n)
}

func (c *LSM303Minimal) magWriteReg(reg, value uint8) error {
	return c.magConn.WriteReg(uint32(reg), []byte{value})
}

func (c *LSM303Minimal) magReadReg(reg uint8) (uint8, error) {
	buf, err := c.magConn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (c *LSM303Minimal) magReadBurst(reg uint8, n int) ([]byte, error) {
	return c.magConn.ReadReg(uint32(reg), n)
}

func (c *LSM303Minimal) settleMS() int {
	if c.rateHz <= 0 {
		return 0
	}
	period := 1000.0 / float64(c.rateHz)
	turnOn := 7.0 * period
	if c.mode == LSM303ModeLowPower {
		turnOn = 1.0
	} else if c.mode == LSM303ModeNormal {
		turnOn = 1.6
	}
	if turnOn < period {
		turnOn = period
	}
	return int(math.Ceil(turnOn))
}

func (c *LSM303Minimal) decodeAxis(low, high uint8) int16 {
	raw := uint16(low) | uint16(high)<<8
	return int16(raw) >> lsm303Shift(c.mode)
}

// ReadAcceleration reads 3-axis acceleration in g.
func (c *LSM303Minimal) ReadAcceleration() (x, y, z float64, err error) {
	raw, err := c.readBurst(lsm303RegOutXL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	scale := lsm303SensitivityMG(c.mode, c.rangeG) / 1000.0
	return float64(c.decodeAxis(raw[0], raw[1])) * scale,
		float64(c.decodeAxis(raw[2], raw[3])) * scale,
		float64(c.decodeAxis(raw[4], raw[5])) * scale, nil
}

// ReadMagnetic reads 3-axis magnetic flux density in µT. The wire order is
// X, Z, Y (high byte first); the result is reordered to X, Y, Z.
func (c *LSM303Minimal) ReadMagnetic() (x, y, z float64, err error) {
	raw, err := c.magReadBurst(lsm303MagRegOutXH, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	gain := lsm303MagGains[c.magCode]
	mx := float64(lsm303ToSigned16(uint16(raw[0])<<8|uint16(raw[1]))) / gain.xy * 100
	mz := float64(lsm303ToSigned16(uint16(raw[2])<<8|uint16(raw[3]))) / gain.z * 100
	my := float64(lsm303ToSigned16(uint16(raw[4])<<8|uint16(raw[5]))) / gain.xy * 100
	return mx, my, mz, nil
}

// LSM303Full extends LSM303Minimal with the complete chip functionality.
type LSM303Full struct {
	LSM303Minimal
	enabled1    uint16
	enabled2    uint16
	unsubscribe func()
}

// NewLSM303Full constructs the full driver and runs the initialization
// sequence.
func NewLSM303Full(accelConn, magConn connection.RegisterConnection) (*LSM303Full, error) {
	m, err := NewLSM303Minimal(accelConn, magConn)
	if err != nil {
		return nil, err
	}
	return &LSM303Full{LSM303Minimal: *m}, nil
}

// SetRange sets the accelerometer full-scale range (2, 4, 8 or 16 g).
func (c *LSM303Full) SetRange(rangeG int) error {
	index := -1
	for i, r := range lsm303Ranges {
		if r == rangeG {
			index = i
		}
	}
	if index < 0 {
		return &LSM303ArgumentError{"rangeG must be one of 2, 4, 8, 16"}
	}
	ctrl4, err := c.readReg(lsm303RegCtrlReg4)
	if err != nil {
		return err
	}
	if err := c.writeReg(lsm303RegCtrlReg4, (ctrl4&^0x30)|uint8(index)<<4); err != nil {
		return err
	}
	c.rangeG = rangeG
	return nil
}

// LSM303ArgumentError reports an invalid argument.
type LSM303ArgumentError struct{ Msg string }

func (e *LSM303ArgumentError) Error() string { return e.Msg }

// SetDataRate sets the accelerometer output data rate in Hz (0 powers down).
func (c *LSM303Full) SetDataRate(rateHz int) error {
	code, ok := lsm303RateCodes[rateHz]
	if !ok {
		return &LSM303ArgumentError{"rateHz must be 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376"}
	}
	if lsm303RateLowPowerOnly[rateHz] && c.mode != LSM303ModeLowPower {
		return &LSM303ArgumentError{"rate is low-power mode only"}
	}
	if lsm303RateNonLowPowerOnly[rateHz] && c.mode == LSM303ModeLowPower {
		return &LSM303ArgumentError{"rate is not available in low-power mode"}
	}
	ctrl1, err := c.readReg(lsm303RegCtrlReg1)
	if err != nil {
		return err
	}
	if err := c.writeReg(lsm303RegCtrlReg1, (ctrl1&0x0F)|code); err != nil {
		return err
	}
	c.rateHz = rateHz
	time.Sleep(time.Duration(c.settleMS()) * time.Millisecond)
	return nil
}

// SetMode sets the accelerometer operating mode.
func (c *LSM303Full) SetMode(mode int) error {
	if mode < 0 || mode > LSM303ModeHighResolution {
		return &LSM303ArgumentError{"mode must be 0, 1 or 2"}
	}
	if mode == LSM303ModeLowPower && lsm303RateNonLowPowerOnly[c.rateHz] {
		return &LSM303ArgumentError{"rate is not available in low-power mode"}
	}
	if mode != LSM303ModeLowPower && lsm303RateLowPowerOnly[c.rateHz] {
		return &LSM303ArgumentError{"rate is low-power mode only"}
	}
	ctrl1, err := c.readReg(lsm303RegCtrlReg1)
	if err != nil {
		return err
	}
	ctrl4, err := c.readReg(lsm303RegCtrlReg4)
	if err != nil {
		return err
	}
	if mode == LSM303ModeLowPower {
		ctrl1 |= 0x08
		ctrl4 &^= 0x08
	} else {
		ctrl1 &^= 0x08
		if mode == LSM303ModeHighResolution {
			ctrl4 |= 0x08
		} else {
			ctrl4 &^= 0x08
		}
	}
	if err := c.writeReg(lsm303RegCtrlReg1, ctrl1); err != nil {
		return err
	}
	if err := c.writeReg(lsm303RegCtrlReg4, ctrl4); err != nil {
		return err
	}
	c.mode = mode
	time.Sleep(time.Duration(c.settleMS()) * time.Millisecond)
	return nil
}

// SetAxes enables or disables the accelerometer axes.
func (c *LSM303Full) SetAxes(x, y, z bool) error {
	ctrl1, err := c.readReg(lsm303RegCtrlReg1)
	if err != nil {
		return err
	}
	var axes uint8
	if x {
		axes |= 0x01
	}
	if y {
		axes |= 0x02
	}
	if z {
		axes |= 0x04
	}
	return c.writeReg(lsm303RegCtrlReg1, (ctrl1&0xF8)|axes)
}

// PowerDown stops accelerometer conversions.
func (c *LSM303Full) PowerDown() error {
	ctrl1, err := c.readReg(lsm303RegCtrlReg1)
	if err != nil {
		return err
	}
	if err := c.writeReg(lsm303RegCtrlReg1, ctrl1&0x0F); err != nil {
		return err
	}
	c.rateHz = 0
	return nil
}

// ReadAccelerationRaw reads raw accelerometer counts.
func (c *LSM303Full) ReadAccelerationRaw() (x, y, z int16, err error) {
	raw, err := c.readBurst(lsm303RegOutXL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return c.decodeAxis(raw[0], raw[1]), c.decodeAxis(raw[2], raw[3]), c.decodeAxis(raw[4], raw[5]), nil
}

// NewDataAvailable reports STATUS_REG_A.ZYXDA.
func (c *LSM303Full) NewDataAvailable() (bool, error) {
	v, err := c.readReg(lsm303RegStatus)
	return v&0x08 != 0, err
}

// ReadStatus reads STATUS_REG_A.
func (c *LSM303Full) ReadStatus() (uint8, error) { return c.readReg(lsm303RegStatus) }

// SetHighPass configures the accelerometer high-pass filter.
func (c *LSM303Full) SetHighPass(mode, cutoff int, filterOutput, onIA1, onIA2, onClick bool) error {
	if mode < 0 || mode > 3 || cutoff < 0 || cutoff > 3 {
		return &LSM303ArgumentError{"mode and cutoff must be 0-3"}
	}
	value := uint8(mode<<6 | cutoff<<4)
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
	return c.writeReg(lsm303RegCtrlReg2, value)
}

// ResetHighPass resets the filter by reading REFERENCE_A.
func (c *LSM303Full) ResetHighPass() (uint8, error) { return c.readReg(lsm303RegReference) }

// SetReference writes the high-pass reference value.
func (c *LSM303Full) SetReference(value uint8) error {
	return c.writeReg(lsm303RegReference, value)
}

// SetFIFO configures the 32-level FIFO.
func (c *LSM303Full) SetFIFO(mode, watermark int, triggerInt2 bool) error {
	if mode < 0 || mode > 3 || watermark < 0 || watermark > 31 {
		return &LSM303ArgumentError{"mode must be 0-3 and watermark 0-31"}
	}
	value := uint8(mode << 6)
	if triggerInt2 {
		value |= 0x20
	}
	value |= uint8(watermark)
	if err := c.writeReg(lsm303RegFifoCtrl, value); err != nil {
		return err
	}
	ctrl5, err := c.readReg(lsm303RegCtrlReg5)
	if err != nil {
		return err
	}
	if mode == LSM303FIFOBypass {
		ctrl5 &^= 0x40
	} else {
		ctrl5 |= 0x40
	}
	return c.writeReg(lsm303RegCtrlReg5, ctrl5)
}

// FIFOLevel returns the number of unread sample sets (0-32).
func (c *LSM303Full) FIFOLevel() (int, error) {
	v, err := c.readReg(lsm303RegFifoSrc)
	return int(v & 0x1F), err
}

// ReadFIFO drains up to count sample sets (count < 0 reads all).
func (c *LSM303Full) ReadFIFO(count int) ([][3]float64, error) {
	level, err := c.FIFOLevel()
	if err != nil {
		return nil, err
	}
	n := level
	if count >= 0 && count < n {
		n = count
	}
	scale := lsm303SensitivityMG(c.mode, c.rangeG) / 1000.0
	out := make([][3]float64, 0, n)
	for i := 0; i < n; i++ {
		raw, err := c.readBurst(lsm303RegOutXL, 6)
		if err != nil {
			return nil, err
		}
		out = append(out, [3]float64{
			float64(c.decodeAxis(raw[0], raw[1])) * scale,
			float64(c.decodeAxis(raw[2], raw[3])) * scale,
			float64(c.decodeAxis(raw[4], raw[5])) * scale,
		})
	}
	return out, nil
}

// ReadFIFOStatus decodes FIFO_SRC_REG_A.
func (c *LSM303Full) ReadFIFOStatus() (watermark, overrun, empty bool, level int, err error) {
	raw, err := c.readReg(lsm303RegFifoSrc)
	if err != nil {
		return false, false, false, 0, err
	}
	return raw&0x80 != 0, raw&0x40 != 0, raw&0x20 != 0, int(raw & 0x1F), nil
}

func (c *LSM303Full) generatorRegs(generator int) (cfg, ths, dur uint8, ok bool) {
	switch generator {
	case 1:
		return lsm303RegInt1Cfg, lsm303RegInt1Ths, lsm303RegInt1Dur, true
	case 2:
		return lsm303RegInt2Cfg, lsm303RegInt2Ths, lsm303RegInt2Dur, true
	default:
		return 0, 0, 0, false
	}
}

func (c *LSM303Full) thresholdCode(thresholdG float64) uint8 {
	code := lsm303Round(thresholdG / (float64(c.rangeG) / 128.0))
	if code < 0 {
		code = 0
	}
	if code > 127 {
		code = 127
	}
	return uint8(code)
}

func (c *LSM303Full) durationCode(durationMS float64, maximum int) uint8 {
	if durationMS <= 0 || c.rateHz <= 0 {
		return 0
	}
	code := lsm303Round(durationMS * float64(c.rateHz) / 1000.0)
	if code < 0 {
		code = 0
	}
	if code > maximum {
		code = maximum
	}
	return uint8(code)
}

// SetMotionInterrupt configures an inertial interrupt generator.
func (c *LSM303Full) SetMotionInterrupt(generator, events int, thresholdG, durationMS float64, andMode bool) error {
	cfgReg, thsReg, durReg, ok := c.generatorRegs(generator)
	if !ok || events < 0 || events > 0x3F {
		return &LSM303ArgumentError{"generator must be 1 or 2 and events 0-0x3F"}
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
	return c.writeReg(durReg, c.durationCode(durationMS, 127))
}

// SetFreeFall configures the free-fall detector (all axes low, AND mode).
func (c *LSM303Full) SetFreeFall(generator int, thresholdG, durationMS float64) error {
	cfgReg, thsReg, durReg, ok := c.generatorRegs(generator)
	if !ok {
		return &LSM303ArgumentError{"generator must be 1 or 2"}
	}
	if err := c.writeReg(cfgReg, 0x95); err != nil {
		return err
	}
	if err := c.writeReg(thsReg, c.thresholdCode(thresholdG)); err != nil {
		return err
	}
	return c.writeReg(durReg, c.durationCode(durationMS, 127))
}

// SetOrientation configures 6D / 4D orientation detection.
func (c *LSM303Full) SetOrientation(generator int, position, fourD bool) error {
	cfgReg, _, _, ok := c.generatorRegs(generator)
	if !ok {
		return &LSM303ArgumentError{"generator must be 1 or 2"}
	}
	zones := uint8(0x3F)
	if fourD {
		zones = 0x0F
	}
	cfg := uint8(0x40) | zones
	if position {
		cfg |= 0x80
	}
	if err := c.writeReg(cfgReg, cfg); err != nil {
		return err
	}
	ctrl5, err := c.readReg(lsm303RegCtrlReg5)
	if err != nil {
		return err
	}
	bit := uint8(0x04)
	if generator == 2 {
		bit = 0x01
	}
	if fourD {
		ctrl5 |= bit
	} else {
		ctrl5 &^= bit
	}
	return c.writeReg(lsm303RegCtrlReg5, ctrl5)
}

// SetLatch latches a generator's interrupt until its source register is read.
func (c *LSM303Full) SetLatch(generator int, enabled bool) error {
	var bit uint8
	switch generator {
	case 1:
		bit = 0x08
	case 2:
		bit = 0x02
	default:
		return &LSM303ArgumentError{"generator must be 1 or 2"}
	}
	ctrl5, err := c.readReg(lsm303RegCtrlReg5)
	if err != nil {
		return err
	}
	if enabled {
		ctrl5 |= bit
	} else {
		ctrl5 &^= bit
	}
	return c.writeReg(lsm303RegCtrlReg5, ctrl5)
}

// ReadInterruptSource reads INTx_SOURCE_A — clears the interrupt.
func (c *LSM303Full) ReadInterruptSource(generator int) (uint8, error) {
	switch generator {
	case 1:
		return c.readReg(lsm303RegInt1Src)
	case 2:
		return c.readReg(lsm303RegInt2Src)
	default:
		return 0, &LSM303ArgumentError{"generator must be 1 or 2"}
	}
}

// SetClick configures single / double click detection.
func (c *LSM303Full) SetClick(single, double int, thresholdG, timeLimitMS, latencyMS, windowMS float64) error {
	if single < 0 || single > 7 || double < 0 || double > 7 {
		return &LSM303ArgumentError{"single and double must be axis masks 0-7"}
	}
	cfg := uint8((single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2) |
		((double & 0x01) << 1) | ((double & 0x02) << 2) | ((double & 0x04) << 3))
	if err := c.writeReg(lsm303RegClickCfg, cfg); err != nil {
		return err
	}
	if err := c.writeReg(lsm303RegClickThs, c.thresholdCode(thresholdG)); err != nil {
		return err
	}
	if err := c.writeReg(lsm303RegTimeLimit, c.durationCode(timeLimitMS, 127)); err != nil {
		return err
	}
	if err := c.writeReg(lsm303RegTimeLatency, c.durationCode(latencyMS, 255)); err != nil {
		return err
	}
	return c.writeReg(lsm303RegTimeWindow, c.durationCode(windowMS, 255))
}

// ReadClickSource reads CLICK_SRC_A — clears the interrupt.
func (c *LSM303Full) ReadClickSource() (uint8, error) { return c.readReg(lsm303RegClickSrc) }

// SetInterruptPolarity sets the shared INT1 / INT2 polarity.
func (c *LSM303Full) SetInterruptPolarity(activeLow bool) error {
	ctrl6, err := c.readReg(lsm303RegCtrlReg6)
	if err != nil {
		return err
	}
	if activeLow {
		ctrl6 |= 0x02
	} else {
		ctrl6 &^= 0x02
	}
	return c.writeReg(lsm303RegCtrlReg6, ctrl6)
}

func lsm303Route(source uint16, pin int) (reg, mask uint8, track int, ok bool) {
	if pin != 1 && pin != 2 {
		return 0, 0, 0, false
	}
	both := lsm303RegCtrlReg3
	if pin == 2 {
		both = lsm303RegCtrlReg6
	}
	switch source {
	case SourceDataReady:
		return lsm303RegCtrlReg3, 0x10, 1, true
	case SourceWatermark:
		return lsm303RegCtrlReg3, 0x04, 1, true
	case SourceOverrun:
		return lsm303RegCtrlReg3, 0x02, 1, true
	case SourceIA1:
		return both, 0x40, pin, true
	case SourceIA2:
		return both, 0x20, pin, true
	case SourceClick:
		return both, 0x80, pin, true
	case SourceBoot:
		return lsm303RegCtrlReg6, 0x10, 2, true
	default:
		return 0, 0, 0, false
	}
}

func lsm303SourceBit(source uint16) uint {
	bit := uint(0)
	for source > 1 {
		source >>= 1
		bit++
	}
	return bit
}

// EnableInterrupt routes one interrupt source to an INT pin.
func (c *LSM303Full) EnableInterrupt(source uint16, pin int) error {
	if source == SourceMagDataReady {
		return &LSM303ArgumentError{"magnetometer DRDY cannot be routed"}
	}
	reg, mask, track, ok := lsm303Route(source, pin)
	if !ok {
		return &LSM303ArgumentError{"unknown source or invalid pin"}
	}
	value, err := c.readReg(reg)
	if err != nil {
		return err
	}
	if err := c.writeReg(reg, value|mask); err != nil {
		return err
	}
	if track == 1 {
		c.enabled1 |= 1 << lsm303SourceBit(source)
	} else {
		c.enabled2 |= 1 << lsm303SourceBit(source)
	}
	return nil
}

// DisableInterrupt removes one interrupt source from an INT pin.
func (c *LSM303Full) DisableInterrupt(source uint16, pin int) error {
	if source == SourceMagDataReady {
		return &LSM303ArgumentError{"magnetometer DRDY cannot be routed"}
	}
	reg, mask, track, ok := lsm303Route(source, pin)
	if !ok {
		return &LSM303ArgumentError{"unknown source or invalid pin"}
	}
	value, err := c.readReg(reg)
	if err != nil {
		return err
	}
	if err := c.writeReg(reg, value&^mask); err != nil {
		return err
	}
	if track == 1 {
		c.enabled1 &^= 1 << lsm303SourceBit(source)
	} else {
		c.enabled2 &^= 1 << lsm303SourceBit(source)
	}
	return nil
}

// PollInterrupt reads every enabled source and returns the combined status.
func (c *LSM303Full) PollInterrupt() (uint16, error) {
	enabled := c.enabled1 | c.enabled2
	var status uint16
	if enabled&0x01 != 0 {
		v, err := c.readReg(lsm303RegStatus)
		if err != nil {
			return 0, err
		}
		if v&0x08 != 0 {
			status |= SourceDataReady
		}
	}
	if enabled&0x06 != 0 {
		fifo, err := c.readReg(lsm303RegFifoSrc)
		if err != nil {
			return 0, err
		}
		if enabled&0x02 != 0 && fifo&0x80 != 0 {
			status |= SourceWatermark
		}
		if enabled&0x04 != 0 && fifo&0x40 != 0 {
			status |= SourceOverrun
		}
	}
	if enabled&0x08 != 0 {
		v, err := c.readReg(lsm303RegInt1Src)
		if err != nil {
			return 0, err
		}
		if v&0x40 != 0 {
			status |= SourceIA1
		}
	}
	if enabled&0x10 != 0 {
		v, err := c.readReg(lsm303RegInt2Src)
		if err != nil {
			return 0, err
		}
		if v&0x40 != 0 {
			status |= SourceIA2
		}
	}
	if enabled&0x20 != 0 {
		v, err := c.readReg(lsm303RegClickSrc)
		if err != nil {
			return 0, err
		}
		if v&0x40 != 0 {
			status |= SourceClick
		}
	}
	sr, err := c.magReadReg(lsm303MagRegOutSR)
	if err != nil {
		return 0, err
	}
	if sr&0x01 != 0 {
		status |= SourceMagDataReady
	}
	return status, nil
}

// OnInterrupt subscribes to an INT pin. It uses the connection's INT pin when
// wired and falls back to a 5 ms polling loop otherwise.
func (c *LSM303Full) OnInterrupt(callback func(status uint16), pin int) error {
	if pin != 1 && pin != 2 {
		return &LSM303ArgumentError{"pin must be 1 or 2"}
	}
	if intPin := c.accelConn.IntPin(); intPin != nil {
		c.unsubscribe = intPin.OnEdge(connection.Falling, func() {
			if status, err := c.PollInterrupt(); err == nil && status != 0 {
				callback(status)
			}
		})
		return nil
	}
	stop := make(chan struct{})
	c.unsubscribe = func() { close(stop) }
	go func() {
		ticker := time.NewTicker(5 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				if status, err := c.PollInterrupt(); err == nil && status != 0 {
					callback(status)
				}
			}
		}
	}()
	return nil
}

// OffInterrupt unsubscribes from the INT pin.
func (c *LSM303Full) OffInterrupt() {
	if c.unsubscribe != nil {
		c.unsubscribe()
		c.unsubscribe = nil
	}
}

// SetMagRange sets the magnetometer gain / full-scale range in µT.
func (c *LSM303Full) SetMagRange(rangeUT int) error {
	code, ok := lsm303MagCodeForRange(rangeUT)
	if !ok {
		return &LSM303ArgumentError{"rangeUT must be 130, 190, 250, 400, 470, 560 or 810"}
	}
	c.magCode = code
	return c.magWriteReg(lsm303MagRegCRB, code)
}

// SetMagDataRate sets the magnetometer output data rate in Hz.
func (c *LSM303Full) SetMagDataRate(rateHz float64) error {
	code, ok := lsm303MagRateCode(rateHz)
	if !ok {
		return &LSM303ArgumentError{"rateHz must be 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220"}
	}
	cra, err := c.magReadReg(lsm303MagRegCRA)
	if err != nil {
		return err
	}
	if err := c.magWriteReg(lsm303MagRegCRA, (cra&0xE3)|code); err != nil {
		return err
	}
	c.magRateHz = rateHz
	return nil
}

// SetMagMode sets the magnetometer operating mode (0 continuous, 1 single,
// 2 sleep).
func (c *LSM303Full) SetMagMode(mode int) error {
	var code uint8
	switch mode {
	case 0:
		code = 0x00
	case 1:
		code = 0x01
	case 2:
		code = 0x03
	default:
		return &LSM303ArgumentError{"mode must be 0, 1 or 2"}
	}
	if err := c.magWriteReg(lsm303MagRegMR, code); err != nil {
		return err
	}
	if mode == 1 {
		time.Sleep(time.Duration(math.Ceil(1000.0/c.magRateHz)) * time.Millisecond)
	}
	return nil
}

// ReadMagneticRaw reads raw magnetometer counts, reordered to X, Y, Z.
func (c *LSM303Full) ReadMagneticRaw() (x, y, z int16, err error) {
	raw, err := c.magReadBurst(lsm303MagRegOutXH, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	mx := lsm303ToSigned16(uint16(raw[0])<<8 | uint16(raw[1]))
	mz := lsm303ToSigned16(uint16(raw[2])<<8 | uint16(raw[3]))
	my := lsm303ToSigned16(uint16(raw[4])<<8 | uint16(raw[5]))
	return mx, my, mz, nil
}

// MagDataReady reports SR_REG_M.DRDY.
func (c *LSM303Full) MagDataReady() (bool, error) {
	v, err := c.magReadReg(lsm303MagRegOutSR)
	return v&0x01 != 0, err
}

// ReadMagStatus decodes SR_REG_M.
func (c *LSM303Full) ReadMagStatus() (drdy, lock bool, err error) {
	v, err := c.magReadReg(lsm303MagRegOutSR)
	if err != nil {
		return false, false, err
	}
	return v&0x01 != 0, v&0x02 != 0, nil
}

// ReadMagIdentity reads IRA/IRB/IRC.
func (c *LSM303Full) ReadMagIdentity() ([]byte, error) {
	return c.magReadBurst(lsm303MagRegIRA, 3)
}

// EnableTemperature enables or disables the magnetometer temperature sensor.
func (c *LSM303Full) EnableTemperature(enabled bool) error {
	cra, err := c.magReadReg(lsm303MagRegCRA)
	if err != nil {
		return err
	}
	if enabled {
		cra |= 0x80
	} else {
		cra &^= 0x80
	}
	return c.magWriteReg(lsm303MagRegCRA, cra)
}

// ReadTemperatureDelta reads the relative magnetometer temperature in °C.
func (c *LSM303Full) ReadTemperatureDelta() (float64, error) {
	cra, err := c.magReadReg(lsm303MagRegCRA)
	if err != nil {
		return 0, err
	}
	if cra&0x80 == 0 {
		if err := c.EnableTemperature(true); err != nil {
			return 0, err
		}
		time.Sleep(time.Duration(math.Ceil(1000.0/c.magRateHz)) * time.Millisecond)
	}
	high, err := c.magReadReg(lsm303MagRegTempH)
	if err != nil {
		return 0, err
	}
	low, err := c.magReadReg(lsm303MagRegTempL)
	if err != nil {
		return 0, err
	}
	raw := int16(uint16(high)<<8 | uint16(low))
	return float64(raw>>4) / 8.0, nil
}

// Heading computes the compass heading in degrees (0-360).
func (c *LSM303Full) Heading(tiltCompensate bool) (float64, error) {
	mx, my, mz, err := c.ReadMagnetic()
	if err != nil {
		return 0, err
	}
	var angle float64
	if tiltCompensate {
		ax, ay, az, err := c.ReadAcceleration()
		if err != nil {
			return 0, err
		}
		roll := math.Atan2(ay, az)
		pitch := math.Atan2(-ax, ay*math.Sin(roll)+az*math.Cos(roll))
		xh := mx*math.Cos(pitch) + mz*math.Sin(pitch)
		yh := mx*math.Sin(roll)*math.Sin(pitch) + my*math.Cos(roll) -
			mz*math.Sin(roll)*math.Cos(pitch)
		angle = math.Atan2(yh, xh)
	} else {
		angle = math.Atan2(my, mx)
	}
	degrees := angle * 180 / math.Pi
	if degrees < 0 {
		degrees += 360
	}
	return degrees, nil
}

// Reboot reboots the accelerometer memory and re-runs the initialization
// sequence.
func (c *LSM303Full) Reboot() error {
	ctrl5, err := c.readReg(lsm303RegCtrlReg5)
	if err != nil {
		return err
	}
	if err := c.writeReg(lsm303RegCtrlReg5, ctrl5|0x80); err != nil {
		return err
	}
	time.Sleep(lsm303BootDelay)
	c.rangeG = LSM303Range2G
	c.mode = LSM303ModeHighResolution
	c.rateHz = 100
	c.magCode = 0x20
	c.magRateHz = 15
	c.enabled1 = 0
	c.enabled2 = 0
	return c.runInitSequence()
}

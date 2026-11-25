// Package accelerometer contains drivers for standalone accelerometers.
package accelerometer

import (
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LSM303 register addresses — accelerometer die (0x19).
const (
	lsm303RegCtrl1A       uint8 = 0x20
	lsm303RegCtrl2A       uint8 = 0x21
	lsm303RegCtrl3A       uint8 = 0x22
	lsm303RegCtrl4A       uint8 = 0x23
	lsm303RegCtrl5A       uint8 = 0x24
	lsm303RegCtrl6A       uint8 = 0x25
	lsm303RegReferenceA   uint8 = 0x26
	lsm303RegStatusA      uint8 = 0x27
	lsm303RegOutXLA       uint8 = 0x28
	lsm303RegFifoCtrlA    uint8 = 0x2E
	lsm303RegFifoSrcA     uint8 = 0x2F
	lsm303RegInt1CfgA     uint8 = 0x30
	lsm303RegInt1SourceA  uint8 = 0x31
	lsm303RegInt1ThsA     uint8 = 0x32
	lsm303RegInt1Duration uint8 = 0x33
	lsm303RegInt2CfgA     uint8 = 0x34
	lsm303RegInt2SourceA  uint8 = 0x35
	lsm303RegInt2ThsA     uint8 = 0x36
	lsm303RegInt2Duration uint8 = 0x37
	lsm303RegClickCfgA    uint8 = 0x38
	lsm303RegClickSrcA    uint8 = 0x39
	lsm303RegClickThsA    uint8 = 0x3A
	lsm303RegTimeLimitA   uint8 = 0x3B
	lsm303RegTimeLatencyA uint8 = 0x3C
	lsm303RegTimeWindowA  uint8 = 0x3D
)

// LSM303 register addresses — magnetometer die (0x1E).
const (
	lsm303RegCraM      uint8 = 0x00
	lsm303RegCrbM      uint8 = 0x01
	lsm303RegMrM       uint8 = 0x02
	lsm303RegOutXHM     uint8 = 0x03
	lsm303RegSrM       uint8 = 0x09
	lsm303RegIraM      uint8 = 0x0A
	lsm303RegTempOutHM uint8 = 0x31
)

// Fixed I²C slave addresses of the two dies.
const (
	// LSM303I2CAddressAccel is the accelerometer slave address.
	LSM303I2CAddressAccel uint8 = 0x19
	// LSM303I2CAddressMag is the magnetometer slave address.
	LSM303I2CAddressMag uint8 = 0x1E
)

// LSM303MagIdentity is the magnetometer identity (IRA/IRB/IRC) of a real part.
var LSM303MagIdentity = [3]byte{0x48, 0x34, 0x33}

// Bit 7 of the accelerometer sub-address enables address auto-increment.
const lsm303AccelAutoIncrement uint8 = 0x80

// Accelerometer operating modes.
const (
	// LSM303ModeLowPower selects the 8-bit low-power output format.
	LSM303ModeLowPower uint8 = 0
	// LSM303ModeNormal selects the 10-bit normal output format.
	LSM303ModeNormal uint8 = 1
	// LSM303ModeHighResolution selects the 12-bit high-resolution output format.
	LSM303ModeHighResolution uint8 = 2
)

// FIFO modes (FIFO_CTRL_REG_A.FM<1:0>).
const (
	// LSM303FifoBypass disables the FIFO.
	LSM303FifoBypass uint8 = 0
	// LSM303FifoFifo keeps the oldest samples and overwrites the newest.
	LSM303FifoFifo uint8 = 1
	// LSM303FifoStream keeps the newest samples.
	LSM303FifoStream uint8 = 2
	// LSM303FifoTrigger stops filling once the trigger fires.
	LSM303FifoTrigger uint8 = 3
)

// Magnetometer operating modes (MR_REG_M.MD<1:0>).
const (
	// LSM303MagModeContinuous runs continuous conversions.
	LSM303MagModeContinuous uint8 = 0
	// LSM303MagModeSingle runs one conversion per request.
	LSM303MagModeSingle uint8 = 1
	// LSM303MagModeSleep stops the conversions.
	LSM303MagModeSleep uint8 = 2
)

// Click detector axis masks.
const (
	// LSM303ClickAxisX enables the X axis click detector.
	LSM303ClickAxisX uint8 = 0x01
	// LSM303ClickAxisY enables the Y axis click detector.
	LSM303ClickAxisY uint8 = 0x02
	// LSM303ClickAxisZ enables the Z axis click detector.
	LSM303ClickAxisZ uint8 = 0x04
)

// Interrupt source bits — the values of PollInterrupt's return mask.
const (
	// LSM303SourceDataReady fires on a new accelerometer XYZ sample.
	LSM303SourceDataReady uint16 = 0x0001
	// LSM303SourceWatermark fires when the FIFO exceeds the watermark.
	LSM303SourceWatermark uint16 = 0x0002
	// LSM303SourceOverrun fires on a FIFO overrun.
	LSM303SourceOverrun uint16 = 0x0004
	// LSM303SourceIA1 fires on an inertial generator 1 event.
	LSM303SourceIA1 uint16 = 0x0008
	// LSM303SourceIA2 fires on an inertial generator 2 event.
	LSM303SourceIA2 uint16 = 0x0010
	// LSM303SourceClick fires on a single or double click.
	LSM303SourceClick uint16 = 0x0020
	// LSM303SourceBoot fires while the boot procedure runs (INT2 only).
	LSM303SourceBoot uint16 = 0x0040
	// LSM303SourceMagDataReady fires on a new magnetometer sample (DRDY pin).
	LSM303SourceMagDataReady uint16 = 0x0080
)

// CTRL_REG1_A bits.
const (
	lsm303Ctrl1OdrMask  uint8 = 0xF0
	lsm303Ctrl1Lpen     uint8 = 0x08
	lsm303Ctrl1AxesMask uint8 = 0x07
)

// CTRL_REG2_A bits.
const (
	lsm303Ctrl2Fds     uint8 = 0x08
	lsm303Ctrl2HpClick uint8 = 0x04
	lsm303Ctrl2HpIs2   uint8 = 0x02
	lsm303Ctrl2HpIs1   uint8 = 0x01
)

// CTRL_REG3_A bits (routing to INT1).
const (
	lsm303Ctrl3I1Click  uint8 = 0x80
	lsm303Ctrl3I1Aoi1   uint8 = 0x40
	lsm303Ctrl3I1Aoi2   uint8 = 0x20
	lsm303Ctrl3I1Drdy1  uint8 = 0x10
	lsm303Ctrl3I1Wtm    uint8 = 0x04
	lsm303Ctrl3I1Overrn uint8 = 0x02
)

// CTRL_REG4_A bits.
const (
	lsm303Ctrl4FsMask uint8 = 0x30
	lsm303Ctrl4Hr     uint8 = 0x08
)

// CTRL_REG5_A bits.
const (
	lsm303Ctrl5Boot     uint8 = 0x80
	lsm303Ctrl5FifoEn   uint8 = 0x40
	lsm303Ctrl5LirInt1  uint8 = 0x08
	lsm303Ctrl5D4dInt1  uint8 = 0x04
	lsm303Ctrl5LirInt2  uint8 = 0x02
	lsm303Ctrl5D4dInt2  uint8 = 0x01
)

// CTRL_REG6_A bits (routing to INT2).
const (
	lsm303Ctrl6I2Click   uint8 = 0x80
	lsm303Ctrl6I2Int1    uint8 = 0x40
	lsm303Ctrl6I2Int2    uint8 = 0x20
	lsm303Ctrl6BootInt1  uint8 = 0x10
	lsm303Ctrl6HLactive  uint8 = 0x02
)

// STATUS_REG_A bits.
const lsm303StatusZyxda uint8 = 0x08

// FIFO_SRC_REG_A bits.
const (
	lsm303FifoSrcWtm        uint8 = 0x80
	lsm303FifoSrcOverrun    uint8 = 0x40
	lsm303FifoSrcEmpty      uint8 = 0x20
	lsm303FifoSrcLevelMask  uint8 = 0x1F
)

// INTx_SOURCE_A / CLICK_SRC_A interrupt-activation bit.
const lsm303SourceIA uint8 = 0x40

// CRA_REG_M bits.
const (
	lsm303CraTempEn uint8 = 0x80
	lsm303CraDoMask uint8 = 0x1C
)

// CRB_REG_M bits.
const lsm303CrbGnMask uint8 = 0xE0

// SR_REG_M bits.
const (
	lsm303SrLock uint8 = 0x02
	lsm303SrDrdy uint8 = 0x01
)

// lsm303MagRates lists the magnetometer output data rates in Hz, indexed by
// the DO field value.
var lsm303MagRates = [8]float32{0.75, 1.5, 3, 7.5, 15, 30, 75, 220}

// LSM303Minimal is the minimal LSM303 interface — acceleration and magnetic
// field.
//
// The constructor runs the datasheet initialization sequence: it verifies the
// magnetometer identity (0x48 0x34 0x33), puts the accelerometer control
// registers into a known state and starts both dies.
//
// Default configuration:
//   - Accelerometer: ±2 *g*, 100 Hz, high-resolution (12-bit), all three axes
//     enabled, BDU set, high-pass filter bypassed, FIFO bypassed, all
//     interrupt routing cleared.
//   - Magnetometer: ±130 µT (±1.3 gauss), 15 Hz, continuous conversion,
//     temperature sensor off.
//
// The two dies sit behind two independent I²C slave addresses, so the driver
// takes two connections.
type LSM303Minimal struct {
	accelConn connection.RegisterConnection
	magConn   connection.RegisterConnection
	mode      uint8
	fsG       uint8
	odrHz     float32
	magRange  uint16
}

// NewLSM303Minimal creates an LSM303Minimal, verifies the magnetometer
// identity and applies the defaults.
func NewLSM303Minimal(accelConn, magConn connection.RegisterConnection) (*LSM303Minimal, error) {
	c := &LSM303Minimal{
		accelConn: accelConn,
		magConn:   magConn,
		mode:      LSM303ModeHighResolution,
		fsG:       2,
		odrHz:     100,
		magRange:  130,
	}
	if err := c.init(); err != nil {
		return nil, err
	}
	return c, nil
}

// init runs the datasheet initialization sequence.
func (c *LSM303Minimal) init() error {
	// Boot download of the factory trim values.
	time.Sleep(5 * time.Millisecond)

	identity, err := c.magRead(lsm303RegIraM, 3)
	if err != nil {
		return err
	}
	if [3]byte{identity[0], identity[1], identity[2]} != LSM303MagIdentity {
		return fmt.Errorf("LSM303 magnetometer not found: expected identity 0x483433, got 0x%02X%02X%02X",
			identity[0], identity[1], identity[2])
	}

	// Accelerometer: known state for the high-pass filter, the interrupt
	// routing and the FIFO.
	for _, reg := range []uint8{lsm303RegCtrl2A, lsm303RegCtrl3A, lsm303RegCtrl5A,
		lsm303RegCtrl6A, lsm303RegFifoCtrlA} {
		if err := c.accelWrite(reg, 0x00); err != nil {
			return err
		}
	}

	// Accelerometer: BDU = 1, ±2 g, high-resolution mode.
	if err := c.accelWrite(lsm303RegCtrl4A, 0x88); err != nil {
		return err
	}
	// Accelerometer: 100 Hz, X/Y/Z enabled.
	if err := c.accelWrite(lsm303RegCtrl1A, 0x57); err != nil {
		return err
	}

	// Magnetometer: 15 Hz, ±1.3 gauss, continuous conversion (the part
	// resets into sleep mode).
	if err := c.magWrite(lsm303RegCraM, 0x10); err != nil {
		return err
	}
	if err := c.magWrite(lsm303RegCrbM, 0x20); err != nil {
		return err
	}
	if err := c.magWrite(lsm303RegMrM, 0x00); err != nil {
		return err
	}

	// Accelerometer turn-on: 7/ODR = 70 ms at 100 Hz.
	time.Sleep(70 * time.Millisecond)
	// Magnetometer first sample: one output period = 67 ms at 15 Hz.
	time.Sleep(67 * time.Millisecond)

	c.mode = LSM303ModeHighResolution
	c.fsG = 2
	c.odrHz = 100
	return nil
}

// accelRead reads length bytes from an accelerometer register.
//
// Multi-byte reads set bit 7 of the sub-address so the accelerometer
// auto-increments the register pointer.
func (c *LSM303Minimal) accelRead(reg uint8, length int) ([]byte, error) {
	addr := reg
	if length > 1 {
		addr |= lsm303AccelAutoIncrement
	}
	return c.accelConn.ReadReg(uint32(addr), length)
}

// accelWrite writes one byte to an accelerometer register.
func (c *LSM303Minimal) accelWrite(reg, value uint8) error {
	return c.accelConn.WriteReg(uint32(reg), []byte{value})
}

// magRead reads length bytes from a magnetometer register.
//
// The magnetometer address pointer always auto-increments, so no
// auto-increment bit is needed.
func (c *LSM303Minimal) magRead(reg uint8, length int) ([]byte, error) {
	return c.magConn.ReadReg(uint32(reg), length)
}

// magWrite writes one byte to a magnetometer register.
func (c *LSM303Minimal) magWrite(reg, value uint8) error {
	return c.magConn.WriteReg(uint32(reg), []byte{value})
}

// accelReadReg reads a single accelerometer register.
func (c *LSM303Minimal) accelReadReg(reg uint8) (uint8, error) {
	b, err := c.accelRead(reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// magReadReg reads a single magnetometer register.
func (c *LSM303Minimal) magReadReg(reg uint8) (uint8, error) {
	b, err := c.magRead(reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// shift returns the right-shift that turns the raw 16-bit pair into counts.
func (c *LSM303Minimal) shift() uint {
	switch c.mode {
	case LSM303ModeLowPower:
		return 8
	case LSM303ModeNormal:
		return 6
	default:
		return 4
	}
}

// sensitivityMg returns the sensitivity in mg/LSB for the current mode and
// range.
func (c *LSM303Minimal) sensitivityMg() float32 {
	var table [4]float32
	switch c.mode {
	case LSM303ModeLowPower:
		table = [4]float32{16, 32, 64, 192}
	case LSM303ModeNormal:
		table = [4]float32{4, 8, 16, 48}
	default:
		table = [4]float32{1, 2, 4, 12}
	}
	index := 0
	switch c.fsG {
	case 4:
		index = 1
	case 8:
		index = 2
	case 16:
		index = 3
	}
	return table[index]
}

// convertAccel converts a six-byte accelerometer burst into (x, y, z) in *g*.
func (c *LSM303Minimal) convertAccel(raw []byte) (float32, float32, float32) {
	shift := c.shift()
	sens := c.sensitivityMg() / 1000
	x := connection.ToSigned(uint32(raw[0])|uint32(raw[1])<<8, 16) >> shift
	y := connection.ToSigned(uint32(raw[2])|uint32(raw[3])<<8, 16) >> shift
	z := connection.ToSigned(uint32(raw[4])|uint32(raw[5])<<8, 16) >> shift
	return float32(x) * sens, float32(y) * sens, float32(z) * sens
}

// rawAccel converts a six-byte accelerometer burst into raw counts.
func (c *LSM303Minimal) rawAccel(raw []byte) (int16, int16, int16) {
	shift := c.shift()
	x := connection.ToSigned(uint32(raw[0])|uint32(raw[1])<<8, 16) >> shift
	y := connection.ToSigned(uint32(raw[2])|uint32(raw[3])<<8, 16) >> shift
	z := connection.ToSigned(uint32(raw[4])|uint32(raw[5])<<8, 16) >> shift
	return int16(x), int16(y), int16(z)
}

// reorderMag reorders a six-byte magnetometer burst (X, Z, Y) into (X, Y, Z).
func (c *LSM303Minimal) reorderMag(raw []byte) (int16, int16, int16) {
	x := connection.ToSigned(uint32(raw[0])<<8|uint32(raw[1]), 16)
	z := connection.ToSigned(uint32(raw[2])<<8|uint32(raw[3]), 16)
	y := connection.ToSigned(uint32(raw[4])<<8|uint32(raw[5]), 16)
	return int16(x), int16(y), int16(z)
}

// magGains returns the (X/Y, Z) gains in LSB per gauss for the current range.
func (c *LSM303Minimal) magGains() (float32, float32) {
	switch c.magRange {
	case 190:
		return 855, 760
	case 250:
		return 670, 600
	case 400:
		return 450, 400
	case 470:
		return 400, 355
	case 560:
		return 330, 295
	case 810:
		return 230, 205
	default:
		return 1100, 980
	}
}

// ReadAcceleration returns the acceleration as (x, y, z) in *g*.
//
// Burst-reads the six output registers so all three axes belong to the same
// sample.
func (c *LSM303Minimal) ReadAcceleration() (float32, float32, float32, error) {
	raw, err := c.accelRead(lsm303RegOutXLA, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	x, y, z := c.convertAccel(raw)
	return x, y, z, nil
}

// ReadMagnetic returns the magnetic flux density as (x, y, z) in µT.
//
// Burst-reads the six output registers (X, Z, Y on the wire) and reorders
// them to X, Y, Z.
func (c *LSM303Minimal) ReadMagnetic() (float32, float32, float32, error) {
	raw, err := c.magRead(lsm303RegOutXHM, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	x, y, z := c.reorderMag(raw)
	gainX, gainZ := c.magGains()
	return float32(x) / gainX * 100, float32(y) / gainX * 100, float32(z) / gainZ * 100, nil
}

// LSM303Full extends LSM303Minimal with the complete chip API.
//
// Adds the accelerometer configuration, the high-pass filter, the FIFO, the
// two inertial interrupt generators, click detection, the magnetometer
// controls, the software compass heading and the interrupt API.
type LSM303Full struct {
	*LSM303Minimal
	enabledSources uint16
	tempEnabled    bool
}

// NewLSM303Full creates an LSM303Full.
func NewLSM303Full(accelConn, magConn connection.RegisterConnection) (*LSM303Full, error) {
	m, err := NewLSM303Minimal(accelConn, magConn)
	if err != nil {
		return nil, err
	}
	return &LSM303Full{LSM303Minimal: m}, nil
}

// SetRange sets the accelerometer full-scale range to 2, 4, 8 or 16 *g*.
func (c *LSM303Full) SetRange(rangeG uint8) error {
	var code uint8
	switch rangeG {
	case 2:
		code = 0x00
	case 4:
		code = 0x10
	case 8:
		code = 0x20
	case 16:
		code = 0x30
	default:
		return fmt.Errorf("SetRange: range must be one of 2, 4, 8, 16")
	}
	ctrl4, err := c.accelReadReg(lsm303RegCtrl4A)
	if err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegCtrl4A, (ctrl4&^lsm303Ctrl4FsMask)|code); err != nil {
		return err
	}
	c.fsG = rangeG
	return nil
}

// SetDataRate sets the accelerometer output data rate in Hz.
//
// The rates 1620 and 5376 Hz exist in low-power mode only, 1344 Hz in normal
// and high-resolution mode only.
func (c *LSM303Full) SetDataRate(rateHz uint16) error {
	var code uint8
	switch rateHz {
	case 0:
		code = 0x0
	case 1:
		code = 0x1
	case 10:
		code = 0x2
	case 25:
		code = 0x3
	case 50:
		code = 0x4
	case 100:
		code = 0x5
	case 200:
		code = 0x6
	case 400:
		code = 0x7
	case 1620:
		code = 0x8
	case 1344, 5376:
		code = 0x9
	default:
		return fmt.Errorf("SetDataRate: rate must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376")
	}
	lowPower := c.mode == LSM303ModeLowPower
	if (rateHz == 1620 || rateHz == 5376) && !lowPower {
		return fmt.Errorf("SetDataRate: %d Hz is only available in low-power mode", rateHz)
	}
	if rateHz == 1344 && lowPower {
		return fmt.Errorf("SetDataRate: 1344 Hz is not available in low-power mode (use 5376 Hz)")
	}
	ctrl1, err := c.accelReadReg(lsm303RegCtrl1A)
	if err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegCtrl1A, (ctrl1&^lsm303Ctrl1OdrMask)|(code<<4)); err != nil {
		return err
	}
	c.odrHz = float32(rateHz)
	return nil
}

// SetMode sets the accelerometer operating mode.
//
// Low-power mode uses 8-bit output, normal mode 10-bit and high-resolution
// mode 12-bit; the sensitivity changes accordingly.
func (c *LSM303Full) SetMode(mode uint8) error {
	var lpen, hr bool
	switch mode {
	case LSM303ModeLowPower:
		lpen, hr = true, false
	case LSM303ModeNormal:
		lpen, hr = false, false
	case LSM303ModeHighResolution:
		lpen, hr = false, true
	default:
		return fmt.Errorf("SetMode: mode must be LSM303ModeLowPower, LSM303ModeNormal or LSM303ModeHighResolution")
	}
	ctrl1, err := c.accelReadReg(lsm303RegCtrl1A)
	if err != nil {
		return err
	}
	ctrl4, err := c.accelReadReg(lsm303RegCtrl4A)
	if err != nil {
		return err
	}
	if lpen {
		ctrl1 |= lsm303Ctrl1Lpen
	} else {
		ctrl1 &^= lsm303Ctrl1Lpen
	}
	if hr {
		ctrl4 |= lsm303Ctrl4Hr
	} else {
		ctrl4 &^= lsm303Ctrl4Hr
	}
	if err := c.accelWrite(lsm303RegCtrl1A, ctrl1); err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegCtrl4A, ctrl4); err != nil {
		return err
	}
	c.mode = mode
	return nil
}

// SetAxes enables or disables the individual accelerometer axes.
func (c *LSM303Full) SetAxes(x, y, z bool) error {
	ctrl1, err := c.accelReadReg(lsm303RegCtrl1A)
	if err != nil {
		return err
	}
	value := ctrl1 &^ lsm303Ctrl1AxesMask
	if x {
		value |= 0x01
	}
	if y {
		value |= 0x02
	}
	if z {
		value |= 0x04
	}
	return c.accelWrite(lsm303RegCtrl1A, value)
}

// PowerDown puts the accelerometer into power-down mode.
//
// The register contents are retained, so SetDataRate resumes the conversions.
func (c *LSM303Full) PowerDown() error {
	ctrl1, err := c.accelReadReg(lsm303RegCtrl1A)
	if err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegCtrl1A, ctrl1&^lsm303Ctrl1OdrMask); err != nil {
		return err
	}
	c.odrHz = 0
	return nil
}

// ReadAccelerationRaw returns the acceleration as raw counts.
func (c *LSM303Full) ReadAccelerationRaw() (int16, int16, int16, error) {
	raw, err := c.accelRead(lsm303RegOutXLA, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	x, y, z := c.rawAccel(raw)
	return x, y, z, nil
}

// NewDataAvailable reports whether a new accelerometer sample is available.
func (c *LSM303Full) NewDataAvailable() (bool, error) {
	status, err := c.accelReadReg(lsm303RegStatusA)
	if err != nil {
		return false, err
	}
	return status&lsm303StatusZyxda != 0, nil
}

// ReadStatus returns the raw accelerometer status register.
func (c *LSM303Full) ReadStatus() (uint8, error) {
	return c.accelReadReg(lsm303RegStatusA)
}

// SetHighPass configures the accelerometer high-pass filter.
//
// mode is the HPM code 0-3 and cutoff the HPCF code 0-3.
func (c *LSM303Full) SetHighPass(mode, cutoff uint8, filterOutput, onIa1, onIa2, onClick bool) error {
	if mode > 3 || cutoff > 3 {
		return fmt.Errorf("SetHighPass: mode and cutoff must be 0..3")
	}
	ctrl2 := mode<<6 | cutoff<<4
	if filterOutput {
		ctrl2 |= lsm303Ctrl2Fds
	}
	if onClick {
		ctrl2 |= lsm303Ctrl2HpClick
	}
	if onIa2 {
		ctrl2 |= lsm303Ctrl2HpIs2
	}
	if onIa1 {
		ctrl2 |= lsm303Ctrl2HpIs1
	}
	return c.accelWrite(lsm303RegCtrl2A, ctrl2)
}

// ResetHighPass resets the high-pass filter and returns the reference value.
func (c *LSM303Full) ResetHighPass() (uint8, error) {
	return c.accelReadReg(lsm303RegReferenceA)
}

// SetReference writes the high-pass reference value.
func (c *LSM303Full) SetReference(value uint8) error {
	return c.accelWrite(lsm303RegReferenceA, value)
}

// SetFifo configures the accelerometer FIFO.
//
// watermark is the level 0-31.
func (c *LSM303Full) SetFifo(mode uint8, watermark uint8, triggerInt2 bool) error {
	if mode > 3 {
		return fmt.Errorf("SetFifo: mode must be 0..3")
	}
	if watermark > 31 {
		return fmt.Errorf("SetFifo: watermark must be 0..31")
	}
	fifo := mode<<6 | (watermark & 0x1F)
	if triggerInt2 {
		fifo |= 0x20
	}
	if err := c.accelWrite(lsm303RegFifoCtrlA, fifo); err != nil {
		return err
	}
	ctrl5, err := c.accelReadReg(lsm303RegCtrl5A)
	if err != nil {
		return err
	}
	if mode == LSM303FifoBypass {
		ctrl5 &^= lsm303Ctrl5FifoEn
	} else {
		ctrl5 |= lsm303Ctrl5FifoEn
	}
	return c.accelWrite(lsm303RegCtrl5A, ctrl5)
}

// FifoLevel returns the number of unread sample sets in the FIFO.
func (c *LSM303Full) FifoLevel() (uint8, error) {
	src, err := c.accelReadReg(lsm303RegFifoSrcA)
	if err != nil {
		return 0, err
	}
	return src & lsm303FifoSrcLevelMask, nil
}

// ReadFifo reads count sample sets from the FIFO.
//
// Each sample set is popped with one six-byte burst read. The samples are
// returned as (x, y, z) acceleration triples in *g*.
func (c *LSM303Full) ReadFifo(count int) ([][3]float32, error) {
	if count < 0 {
		count = 0
	}
	out := make([][3]float32, 0, count)
	for i := 0; i < count; i++ {
		raw, err := c.accelRead(lsm303RegOutXLA, 6)
		if err != nil {
			return nil, err
		}
		x, y, z := c.convertAccel(raw)
		out = append(out, [3]float32{x, y, z})
	}
	return out, nil
}

// ReadFifoStatus returns the decoded FIFO status.
func (c *LSM303Full) ReadFifoStatus() (watermark, overrun, empty bool, level uint8, err error) {
	src, err := c.accelReadReg(lsm303RegFifoSrcA)
	if err != nil {
		return false, false, false, 0, err
	}
	return src&lsm303FifoSrcWtm != 0, src&lsm303FifoSrcOverrun != 0,
		src&lsm303FifoSrcEmpty != 0, src & lsm303FifoSrcLevelMask, nil
}

// intRegs returns the (config, threshold, duration) register triple of a
// generator.
func lsm303IntRegs(generator uint8) (cfg, ths, dur uint8, err error) {
	switch generator {
	case 1:
		return lsm303RegInt1CfgA, lsm303RegInt1ThsA, lsm303RegInt1Duration, nil
	case 2:
		return lsm303RegInt2CfgA, lsm303RegInt2ThsA, lsm303RegInt2Duration, nil
	default:
		return 0, 0, 0, fmt.Errorf("generator must be 1 or 2")
	}
}

// encodeThreshold converts a threshold in *g* into a 7-bit register code.
func (c *LSM303Full) encodeThreshold(thresholdG float32) uint8 {
	code := int32(thresholdG*128/float32(c.fsG) + 0.5)
	if code < 0 {
		code = 0
	}
	if code > 127 {
		code = 127
	}
	return uint8(code)
}

// encodeDuration converts a duration in milliseconds into a register code.
//
// One LSB is 1/ODR; the code is clamped to the register width.
func (c *LSM303Full) encodeDuration(durationMs float32, bits uint) uint8 {
	var code int32
	if c.odrHz > 0 {
		code = int32(durationMs*c.odrHz/1000 + 0.5)
	}
	maxValue := int32(1<<bits) - 1
	if code < 0 {
		code = 0
	}
	if code > maxValue {
		code = maxValue
	}
	return uint8(code)
}

// SetMotionInterrupt configures one inertial interrupt generator.
//
// events is a 6-bit mask of the enabled axis events, thresholdG the threshold
// relative to the current range and durationMs the minimum event duration.
func (c *LSM303Full) SetMotionInterrupt(generator, events uint8, thresholdG, durationMs float32, andMode bool) error {
	cfg, ths, dur, err := lsm303IntRegs(generator)
	if err != nil {
		return err
	}
	if events > 0x3F {
		return fmt.Errorf("SetMotionInterrupt: events must be a 6-bit mask (0..63)")
	}
	value := events & 0x3F
	if andMode {
		value |= 0x80
	}
	if err := c.accelWrite(cfg, value); err != nil {
		return err
	}
	if err := c.accelWrite(ths, c.encodeThreshold(thresholdG)); err != nil {
		return err
	}
	return c.accelWrite(dur, c.encodeDuration(durationMs, 7))
}

// SetFreeFall arms the free-fall detector of one generator.
//
// All three axes are watched for a low event and combined with AND.
func (c *LSM303Full) SetFreeFall(generator uint8, thresholdG, durationMs float32) error {
	return c.SetMotionInterrupt(generator, 0x15, thresholdG, durationMs, true)
}

// SetOrientation arms the 6D orientation detector of one generator.
//
// position selects position recognition (a level) over movement recognition
// (a one-ODR pulse); fourD drops the Z axis.
func (c *LSM303Full) SetOrientation(generator uint8, position, fourD bool) error {
	cfg, _, _, err := lsm303IntRegs(generator)
	if err != nil {
		return err
	}
	events := uint8(0x3F)
	if fourD {
		events &^= 0x30
	}
	value := events | 0x40
	if position {
		value |= 0x80
	}
	if err := c.accelWrite(cfg, value); err != nil {
		return err
	}
	ctrl5, err := c.accelReadReg(lsm303RegCtrl5A)
	if err != nil {
		return err
	}
	bit := lsm303Ctrl5D4dInt1
	if generator == 2 {
		bit = lsm303Ctrl5D4dInt2
	}
	if fourD {
		ctrl5 |= bit
	} else {
		ctrl5 &^= bit
	}
	return c.accelWrite(lsm303RegCtrl5A, ctrl5)
}

// SetLatch latches or releases the interrupt of one generator.
func (c *LSM303Full) SetLatch(generator uint8, enabled bool) error {
	if generator != 1 && generator != 2 {
		return fmt.Errorf("SetLatch: generator must be 1 or 2")
	}
	ctrl5, err := c.accelReadReg(lsm303RegCtrl5A)
	if err != nil {
		return err
	}
	bit := lsm303Ctrl5LirInt1
	if generator == 2 {
		bit = lsm303Ctrl5LirInt2
	}
	if enabled {
		ctrl5 |= bit
	} else {
		ctrl5 &^= bit
	}
	return c.accelWrite(lsm303RegCtrl5A, ctrl5)
}

// ReadInterruptSource reads the interrupt source register of one generator.
//
// Reading the register clears the latched interrupt.
func (c *LSM303Full) ReadInterruptSource(generator uint8) (uint8, error) {
	if generator != 1 && generator != 2 {
		return 0, fmt.Errorf("ReadInterruptSource: generator must be 1 or 2")
	}
	reg := lsm303RegInt1SourceA
	if generator == 2 {
		reg = lsm303RegInt2SourceA
	}
	return c.accelReadReg(reg)
}

// SetClick configures the click detector.
//
// single and doubleClick are axis masks built from LSM303ClickAxisX,
// LSM303ClickAxisY and LSM303ClickAxisZ.
func (c *LSM303Full) SetClick(single, doubleClick uint8, thresholdG, timeLimitMs, latencyMs, windowMs float32) error {
	if single > 0x07 || doubleClick > 0x07 {
		return fmt.Errorf("SetClick: the axis masks must be 0..7")
	}
	var cfg uint8
	if single&LSM303ClickAxisX != 0 {
		cfg |= 0x01
	}
	if single&LSM303ClickAxisY != 0 {
		cfg |= 0x04
	}
	if single&LSM303ClickAxisZ != 0 {
		cfg |= 0x10
	}
	if doubleClick&LSM303ClickAxisX != 0 {
		cfg |= 0x02
	}
	if doubleClick&LSM303ClickAxisY != 0 {
		cfg |= 0x08
	}
	if doubleClick&LSM303ClickAxisZ != 0 {
		cfg |= 0x20
	}
	if err := c.accelWrite(lsm303RegClickCfgA, cfg); err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegClickThsA, c.encodeThreshold(thresholdG)); err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegTimeLimitA, c.encodeDuration(timeLimitMs, 7)); err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegTimeLatencyA, c.encodeDuration(latencyMs, 8)); err != nil {
		return err
	}
	return c.accelWrite(lsm303RegTimeWindowA, c.encodeDuration(windowMs, 8))
}

// ReadClickSource reads the click source register.
//
// Reading the register clears the latched click interrupt.
func (c *LSM303Full) ReadClickSource() (uint8, error) {
	return c.accelReadReg(lsm303RegClickSrcA)
}

// SetInterruptPolarity sets the polarity of the INT1 / INT2 outputs.
func (c *LSM303Full) SetInterruptPolarity(activeLow bool) error {
	ctrl6, err := c.accelReadReg(lsm303RegCtrl6A)
	if err != nil {
		return err
	}
	if activeLow {
		ctrl6 |= lsm303Ctrl6HLactive
	} else {
		ctrl6 &^= lsm303Ctrl6HLactive
	}
	return c.accelWrite(lsm303RegCtrl6A, ctrl6)
}

// SetMagRange sets the magnetometer full-scale range in µT.
func (c *LSM303Full) SetMagRange(rangeUt uint16) error {
	var code uint8
	switch rangeUt {
	case 130:
		code = 0x20
	case 190:
		code = 0x40
	case 250:
		code = 0x60
	case 400:
		code = 0x80
	case 470:
		code = 0xA0
	case 560:
		code = 0xC0
	case 810:
		code = 0xE0
	default:
		return fmt.Errorf("SetMagRange: range must be one of 130, 190, 250, 400, 470, 560, 810")
	}
	crb, err := c.magReadReg(lsm303RegCrbM)
	if err != nil {
		return err
	}
	if err := c.magWrite(lsm303RegCrbM, (crb&^lsm303CrbGnMask)|code); err != nil {
		return err
	}
	c.magRange = rangeUt
	return nil
}

// SetMagDataRate sets the magnetometer output data rate in Hz.
func (c *LSM303Full) SetMagDataRate(rateHz float32) error {
	for code, rate := range lsm303MagRates {
		if math.Abs(float64(rate-rateHz)) < 1e-6 {
			cra, err := c.magReadReg(lsm303RegCraM)
			if err != nil {
				return err
			}
			return c.magWrite(lsm303RegCraM, (cra&^lsm303CraDoMask)|(uint8(code)<<2))
		}
	}
	return fmt.Errorf("SetMagDataRate: rate must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220")
}

// SetMagMode sets the magnetometer operating mode.
func (c *LSM303Full) SetMagMode(mode uint8) error {
	if mode > LSM303MagModeSleep {
		return fmt.Errorf("SetMagMode: mode must be LSM303MagModeContinuous, LSM303MagModeSingle or LSM303MagModeSleep")
	}
	mr, err := c.magReadReg(lsm303RegMrM)
	if err != nil {
		return err
	}
	return c.magWrite(lsm303RegMrM, (mr&^0x03)|mode)
}

// ReadMagneticRaw returns the magnetic field as raw counts in X, Y, Z order.
func (c *LSM303Full) ReadMagneticRaw() (int16, int16, int16, error) {
	raw, err := c.magRead(lsm303RegOutXHM, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	x, y, z := c.reorderMag(raw)
	return x, y, z, nil
}

// MagDataReady reports whether a new magnetometer sample is available.
func (c *LSM303Full) MagDataReady() (bool, error) {
	sr, err := c.magReadReg(lsm303RegSrM)
	if err != nil {
		return false, err
	}
	return sr&lsm303SrDrdy != 0, nil
}

// ReadMagStatus returns the decoded magnetometer status.
func (c *LSM303Full) ReadMagStatus() (drdy, lock bool, err error) {
	sr, err := c.magReadReg(lsm303RegSrM)
	if err != nil {
		return false, false, err
	}
	return sr&lsm303SrDrdy != 0, sr&lsm303SrLock != 0, nil
}

// ReadMagIdentity returns the magnetometer identity bytes.
func (c *LSM303Full) ReadMagIdentity() ([3]byte, error) {
	var out [3]byte
	b, err := c.magRead(lsm303RegIraM, 3)
	if err != nil {
		return out, err
	}
	copy(out[:], b)
	return out, nil
}

// EnableTemperature enables or disables the magnetometer temperature sensor.
func (c *LSM303Full) EnableTemperature(enabled bool) error {
	cra, err := c.magReadReg(lsm303RegCraM)
	if err != nil {
		return err
	}
	if enabled {
		cra |= lsm303CraTempEn
	} else {
		cra &^= lsm303CraTempEn
	}
	if err := c.magWrite(lsm303RegCraM, cra); err != nil {
		return err
	}
	c.tempEnabled = enabled
	return nil
}

// ReadTemperatureDelta returns the relative temperature of the magnetometer
// in °C.
//
// The sensor is enabled on first use. The datasheet gives no zero reference,
// so the value is a delta relative to an unspecified temperature and not an
// absolute °C reading.
func (c *LSM303Full) ReadTemperatureDelta() (float32, error) {
	if !c.tempEnabled {
		if err := c.EnableTemperature(true); err != nil {
			return 0, err
		}
	}
	raw, err := c.magRead(lsm303RegTempOutHM, 2)
	if err != nil {
		return 0, err
	}
	value := connection.ToSigned(uint32(raw[0])<<8|uint32(raw[1]), 16) >> 4
	return float32(value) / 8, nil
}

// Heading returns the compass heading in degrees, 0-360.
//
// With tiltCompensate the heading is corrected for the tilt reported by the
// accelerometer; without it the flat (magnetic-only) heading is returned.
func (c *LSM303Full) Heading(tiltCompensate bool) (float32, error) {
	mx, my, mz, err := c.ReadMagnetic()
	if err != nil {
		return 0, err
	}
	if !tiltCompensate {
		return lsm303WrapDegrees(float32(math.Atan2(float64(my), float64(mx))) * 180 / math.Pi), nil
	}
	ax, ay, az, err := c.ReadAcceleration()
	if err != nil {
		return 0, err
	}
	roll := float32(math.Atan2(float64(ay), float64(az)))
	pitch := float32(math.Atan2(float64(-ax),
		float64(ay*float32(math.Sin(float64(roll)))+az*float32(math.Cos(float64(roll))))))
	xh := mx*float32(math.Cos(float64(pitch))) + mz*float32(math.Sin(float64(pitch)))
	yh := mx*float32(math.Sin(float64(roll)))*float32(math.Sin(float64(pitch))) +
		my*float32(math.Cos(float64(roll))) -
		mz*float32(math.Sin(float64(roll)))*float32(math.Cos(float64(pitch)))
	return lsm303WrapDegrees(float32(math.Atan2(float64(yh), float64(xh))) * 180 / math.Pi), nil
}

// lsm303WrapDegrees wraps an angle in degrees into the 0-360 range.
func lsm303WrapDegrees(degrees float32) float32 {
	value := float32(math.Mod(float64(degrees), 360))
	if value < 0 {
		value += 360
	}
	return value
}

// sourceBits returns the (CTRL_REG3_A, CTRL_REG6_A) routing bits of a
// source.
func lsm303SourceBits(source uint16) (ctrl3, ctrl6 uint8, ok bool) {
	switch source {
	case LSM303SourceDataReady:
		return lsm303Ctrl3I1Drdy1, 0, true
	case LSM303SourceWatermark:
		return lsm303Ctrl3I1Wtm, 0, true
	case LSM303SourceOverrun:
		return lsm303Ctrl3I1Overrn, 0, true
	case LSM303SourceIA1:
		return lsm303Ctrl3I1Aoi1, lsm303Ctrl6I2Int1, true
	case LSM303SourceIA2:
		return lsm303Ctrl3I1Aoi2, lsm303Ctrl6I2Int2, true
	case LSM303SourceClick:
		return lsm303Ctrl3I1Click, lsm303Ctrl6I2Click, true
	case LSM303SourceBoot:
		return 0, lsm303Ctrl6BootInt1, true
	default:
		return 0, 0, false
	}
}

// EnableInterrupt routes one interrupt source to INT1 or INT2.
//
// Only the routing is configured; the generator or function behind the source
// is set up with the Set* methods.
func (c *LSM303Full) EnableInterrupt(source uint16, pin uint8) error {
	if pin != 1 && pin != 2 {
		return fmt.Errorf("EnableInterrupt: pin must be 1 or 2")
	}
	if source == LSM303SourceMagDataReady {
		return fmt.Errorf("EnableInterrupt: LSM303SourceMagDataReady is reported on the DRDY pin and cannot be routed")
	}
	ctrl3Bit, ctrl6Bit, ok := lsm303SourceBits(source)
	if !ok {
		return fmt.Errorf("EnableInterrupt: unknown interrupt source")
	}
	if ctrl3Bit != 0 && (pin == 1 || ctrl6Bit == 0) {
		ctrl3, err := c.accelReadReg(lsm303RegCtrl3A)
		if err != nil {
			return err
		}
		if err := c.accelWrite(lsm303RegCtrl3A, ctrl3|ctrl3Bit); err != nil {
			return err
		}
	}
	if ctrl6Bit != 0 && (pin == 2 || ctrl3Bit == 0) {
		ctrl6, err := c.accelReadReg(lsm303RegCtrl6A)
		if err != nil {
			return err
		}
		if err := c.accelWrite(lsm303RegCtrl6A, ctrl6|ctrl6Bit); err != nil {
			return err
		}
	}
	c.enabledSources |= source
	return nil
}

// DisableInterrupt removes one interrupt source from INT1 or INT2.
func (c *LSM303Full) DisableInterrupt(source uint16, pin uint8) error {
	if pin != 1 && pin != 2 {
		return fmt.Errorf("DisableInterrupt: pin must be 1 or 2")
	}
	if source == LSM303SourceMagDataReady {
		return fmt.Errorf("DisableInterrupt: LSM303SourceMagDataReady is reported on the DRDY pin and cannot be routed")
	}
	ctrl3Bit, ctrl6Bit, ok := lsm303SourceBits(source)
	if !ok {
		return fmt.Errorf("DisableInterrupt: unknown interrupt source")
	}
	if ctrl3Bit != 0 && (pin == 1 || ctrl6Bit == 0) {
		ctrl3, err := c.accelReadReg(lsm303RegCtrl3A)
		if err != nil {
			return err
		}
		if err := c.accelWrite(lsm303RegCtrl3A, ctrl3&^ctrl3Bit); err != nil {
			return err
		}
	}
	if ctrl6Bit != 0 && (pin == 2 || ctrl3Bit == 0) {
		ctrl6, err := c.accelReadReg(lsm303RegCtrl6A)
		if err != nil {
			return err
		}
		if err := c.accelWrite(lsm303RegCtrl6A, ctrl6&^ctrl6Bit); err != nil {
			return err
		}
	}
	c.enabledSources &^= source
	return nil
}

// PollInterrupt reads the interrupt status of every enabled source.
//
// The return value is a mask of the LSM303Source* constants.
func (c *LSM303Full) PollInterrupt() (uint16, error) {
	var status uint16
	regStatus, err := c.accelReadReg(lsm303RegStatusA)
	if err != nil {
		return 0, err
	}
	if regStatus&lsm303StatusZyxda != 0 {
		status |= LSM303SourceDataReady
	}
	fifo, err := c.accelReadReg(lsm303RegFifoSrcA)
	if err != nil {
		return 0, err
	}
	if fifo&lsm303FifoSrcWtm != 0 {
		status |= LSM303SourceWatermark
	}
	if fifo&lsm303FifoSrcOverrun != 0 {
		status |= LSM303SourceOverrun
	}
	if c.enabledSources&LSM303SourceIA1 != 0 {
		src, err := c.accelReadReg(lsm303RegInt1SourceA)
		if err != nil {
			return 0, err
		}
		if src&lsm303SourceIA != 0 {
			status |= LSM303SourceIA1
		}
	}
	if c.enabledSources&LSM303SourceIA2 != 0 {
		src, err := c.accelReadReg(lsm303RegInt2SourceA)
		if err != nil {
			return 0, err
		}
		if src&lsm303SourceIA != 0 {
			status |= LSM303SourceIA2
		}
	}
	if c.enabledSources&LSM303SourceClick != 0 {
		src, err := c.accelReadReg(lsm303RegClickSrcA)
		if err != nil {
			return 0, err
		}
		if src&lsm303SourceIA != 0 {
			status |= LSM303SourceClick
		}
	}
	sr, err := c.magReadReg(lsm303RegSrM)
	if err != nil {
		return 0, err
	}
	if sr&lsm303SrDrdy != 0 {
		status |= LSM303SourceMagDataReady
	}
	return status, nil
}

// Reboot reboots the accelerometer's memory content.
//
// The BOOT bit reloads the factory trim values. The initialization sequence
// is run again afterwards, so any configuration applied after the
// constructor has to be repeated.
func (c *LSM303Full) Reboot() error {
	ctrl5, err := c.accelReadReg(lsm303RegCtrl5A)
	if err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegCtrl5A, ctrl5|lsm303Ctrl5Boot); err != nil {
		return err
	}
	time.Sleep(5 * time.Millisecond)
	if err := c.init(); err != nil {
		return err
	}
	c.tempEnabled = false
	return nil
}

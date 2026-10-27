// Package accelerometer contains drivers for standalone accelerometers.
package accelerometer

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LSM303 accelerometer register addresses (sub-address bit 7 = auto-increment).
const (
	lsm303RegCtrl1A     uint8 = 0x20
	lsm303RegCtrl2A     uint8 = 0x21
	lsm303RegCtrl3A     uint8 = 0x22
	lsm303RegCtrl4A     uint8 = 0x23
	lsm303RegCtrl5A     uint8 = 0x24
	lsm303RegCtrl6A     uint8 = 0x25
	lsm303RegReferenceA uint8 = 0x26
	lsm303RegStatusA    uint8 = 0x27
	lsm303RegOutXLA     uint8 = 0x28
	lsm303RegFifoCtrlA  uint8 = 0x2E
	lsm303RegFifoSrcA   uint8 = 0x2F
	lsm303RegInt1CfgA   uint8 = 0x30
	lsm303RegInt1SrcA   uint8 = 0x31
	lsm303RegInt1ThsA   uint8 = 0x32
	lsm303RegInt1DurA   uint8 = 0x33
	lsm303RegInt2CfgA   uint8 = 0x34
	lsm303RegInt2SrcA   uint8 = 0x35
	lsm303RegInt2ThsA   uint8 = 0x36
	lsm303RegInt2DurA   uint8 = 0x37
	lsm303RegClickCfgA  uint8 = 0x38
	lsm303RegClickSrcA  uint8 = 0x39
	lsm303RegClickThsA  uint8 = 0x3A
	lsm303RegTimeLimitA uint8 = 0x3B
	lsm303RegTimeLatA   uint8 = 0x3C
	lsm303RegTimeWinA   uint8 = 0x3D
)

// LSM303 magnetometer register addresses (address pointer auto-increments;
// no auto-increment bit, unlike the accelerometer).
const (
	lsm303RegCraM   uint8 = 0x00
	lsm303RegCrbM   uint8 = 0x01
	lsm303RegMrM    uint8 = 0x02
	lsm303RegOutXHM uint8 = 0x03
	lsm303RegSrM    uint8 = 0x09
	lsm303RegIraM   uint8 = 0x0A
	lsm303RegTempHM uint8 = 0x31
)

// lsm303MagID is the magnetometer identity IRA/IRB/IRC (ASCII "H43") —
// identical to HMC5883L, so the two chips cannot be told apart by ID.
var lsm303MagID = [3]byte{0x48, 0x34, 0x33}

// Valid accelerometer output data rates in Hz (CTRL_REG1_A ODR bits 7:4).
// There is no entry for 0 Hz: use PowerDown for power-down. Code 0x8 exists
// in low-power mode only; code 0x9 is 1344 Hz in normal / high-resolution
// and 5376 Hz in low-power.
var lsm303AccelOdrCodes = []struct {
	hz   int
	code uint8
}{
	{1, 0x1}, {10, 0x2}, {25, 0x3}, {50, 0x4}, {100, 0x5},
	{200, 0x6}, {400, 0x7}, {1620, 0x8}, {1344, 0x9}, {5376, 0x9},
}

// Sensitivity in mg/LSB, indexed [mode][rangeBits]; mode 0 = low-power
// (8-bit), 1 = normal (10-bit), 2 = high-resolution (12-bit); range
// 0..3 = ±2/4/8/16 *g*.
var lsm303AccelSensitivityMg = [3][4]float64{
	{16.0, 32.0, 64.0, 192.0},
	{4.0, 8.0, 16.0, 48.0},
	{1.0, 2.0, 4.0, 12.0},
}

// Arithmetic right-shift per mode: 8-bit low-power → 8, 10-bit normal → 6,
// 12-bit high-resolution → 4.
var lsm303AccelShift = [3]uint{8, 6, 4}

// Magnetometer gain codes GN<2:0> 1..7: full scale in µT and LSB/gauss for
// X/Y and Z (Z uses a different gain at every setting).
var lsm303MagRanges = []struct {
	fullScaleUT int
	gainXY      float64
	gainZ       float64
}{
	{130, 1100.0, 980.0},
	{190, 855.0, 760.0},
	{250, 670.0, 600.0},
	{400, 450.0, 400.0},
	{470, 400.0, 355.0},
	{560, 330.0, 295.0},
	{810, 230.0, 205.0},
}

// Valid magnetometer output data rates in Hz (CRA_REG_M DO bits 4:2).
var lsm303MagOdrCodes = []struct {
	hz   float64
	code uint8
}{
	{0.75, 0x0}, {1.5, 0x1}, {3.0, 0x2}, {7.5, 0x3},
	{15.0, 0x4}, {30.0, 0x5}, {75.0, 0x6}, {220.0, 0x7},
}

// CTRL_REG3_A routing bits (INT1 side).
const (
	lsm303I1Click   uint8 = 0x80
	lsm303I1Aoi1    uint8 = 0x40
	lsm303I1Aoi2    uint8 = 0x20
	lsm303I1Drdy1   uint8 = 0x10
	lsm303I1Wtm     uint8 = 0x04
	lsm303I1Overrun uint8 = 0x01
)

// CTRL_REG6_A routing bits (INT2 side).
const (
	lsm303I2Click uint8 = 0x80
	lsm303I2Int1  uint8 = 0x40
	lsm303I2Int2  uint8 = 0x20
	lsm303BootI1  uint8 = 0x10
)

// Interrupt source bits — the PollInterrupt status layout.
const (
	// SourceDataReady is a new accelerometer XYZ sample (STATUS_REG_A.ZYXDA) on INT1.
	SourceDataReady uint16 = 0x0001
	// SourceWatermark is FIFO content exceeding the watermark (FIFO_SRC_REG_A.WTM) on INT1.
	SourceWatermark uint16 = 0x0002
	// SourceOverrun is a FIFO overrun (FIFO_SRC_REG_A.OVRN_FIFO) on INT1.
	SourceOverrun uint16 = 0x0004
	// SourceIA1 is an inertial generator 1 event (INT1_SOURCE_A.IA) on INT1 or INT2.
	SourceIA1 uint16 = 0x0008
	// SourceIA2 is an inertial generator 2 event (INT2_SOURCE_A.IA) on INT1 or INT2.
	SourceIA2 uint16 = 0x0010
	// SourceClick is a single/double click (CLICK_SRC_A.IA) on INT1 or INT2.
	SourceClick uint16 = 0x0020
	// SourceBoot indicates the boot procedure running (INT2 only, pin-only —
	// never reported by PollInterrupt).
	SourceBoot uint16 = 0x0040
	// SourceMagDataReady is a new magnetometer sample (SR_REG_M.DRDY) on the
	// dedicated DRDY pin — not routable to INT1/INT2.
	SourceMagDataReady uint16 = 0x0080
)

// LSM303Minimal is the LSM303DLHC 3-axis accelerometer + 3-axis magnetometer
// e-compass — minimal interface.
//
// Reads 3-axis acceleration in *g* and 3-axis magnetic flux density in µT
// with sensible defaults; no configuration is required beyond the two
// connections. The module is one logical device with two connections (the
// same pattern as MPU9250): the accelerometer slave at 0x19 and the
// magnetometer slave at 0x1E, both as RegisterConnection on the same bus.
//
// Default configuration (written at construction):
//   - Accelerometer: ±2 *g* (1 mg/LSB), 100 Hz ODR, high-resolution mode,
//     all axes enabled, BDU=1, high-pass filter bypassed, FIFO bypassed,
//     all interrupt routing cleared
//   - Magnetometer: ±1.3 gauss (±130 µT), 15 Hz ODR, continuous conversion,
//     temperature sensor off
//
// The two slaves use different I²C framing: the accelerometer sub-address
// bit 7 enables address auto-increment (set for every multi-byte transfer);
// the magnetometer has an address pointer that always auto-increments and
// needs no extra bit. Magnetometer outputs are X, Z, Y with the high byte
// first — reordered to X, Y, Z. Accelerometer outputs are X, Y, Z low byte
// first.
type LSM303Minimal struct {
	accel       connection.RegisterConnection
	mag         connection.RegisterConnection
	rangeBits   uint8 // 0..3: ±2/4/8/16 *g*
	mode        uint8 // 0 low-power, 1 normal, 2 high-resolution
	odrHz       float64
	magGN       uint8 // 0..6 index into lsm303MagRanges
	magOdrHz    float64
	tempEnabled bool
}

// NewLSM303Minimal creates an LSM303Minimal, verifies the magnetometer
// identity, and applies the default configuration to both slaves.
//
// accelConn must be bound to the accelerometer slave address 0x19 and
// magConn to the magnetometer slave address 0x1E, on the same bus.
func NewLSM303Minimal(accelConn, magConn connection.RegisterConnection) (*LSM303Minimal, error) {
	c := &LSM303Minimal{
		accel:     accelConn,
		mag:       magConn,
		rangeBits: 0,
		mode:      2,
		odrHz:     100.0,
		magGN:     0,
		magOdrHz:  15.0,
	}

	// Boot download of the factory trim values (≥ 5 ms, as for sibling parts).
	time.Sleep(5 * time.Millisecond)

	id, err := c.readBurstMag(lsm303RegIraM, 3)
	if err != nil {
		return nil, err
	}
	if id[0] != lsm303MagID[0] || id[1] != lsm303MagID[1] || id[2] != lsm303MagID[2] {
		return nil, fmt.Errorf("LSM303 magnetometer ID: expected 0x483433, got 0x%02X%02X%02X",
			id[0], id[1], id[2])
	}

	// Accelerometer: no soft reset — write the control registers to known values.
	if err := c.writeRegAccel(lsm303RegCtrl2A, 0x00); err != nil {
		return nil, err
	}
	if err := c.writeRegAccel(lsm303RegCtrl3A, 0x00); err != nil {
		return nil, err
	}
	if err := c.writeRegAccel(lsm303RegCtrl5A, 0x00); err != nil {
		return nil, err
	}
	if err := c.writeRegAccel(lsm303RegCtrl6A, 0x00); err != nil {
		return nil, err
	}
	if err := c.writeRegAccel(lsm303RegFifoCtrlA, 0x00); err != nil {
		return nil, err
	}
	// BDU=1, FS=00 (±2 *g*), HR=1 (high-resolution).
	if err := c.writeRegAccel(lsm303RegCtrl4A, 0x88); err != nil {
		return nil, err
	}
	// ODR=0101 (100 Hz), LPen=0, all axes enabled — leaves power-down.
	if err := c.writeRegAccel(lsm303RegCtrl1A, 0x57); err != nil {
		return nil, err
	}

	// Magnetometer: the part resets into sleep mode, so write all three
	// control registers explicitly (15 Hz, ±1.3 gauss, continuous).
	if err := c.writeRegMag(lsm303RegCraM, 0x10); err != nil {
		return nil, err
	}
	if err := c.writeRegMag(lsm303RegCrbM, 0x20); err != nil {
		return nil, err
	}
	if err := c.writeRegMag(lsm303RegMrM, 0x00); err != nil {
		return nil, err
	}

	// Turn-on waits: 7/ODR (70 ms at 100 Hz) for the accelerometer and one
	// output period (67 ms at 15 Hz) for the first magnetometer sample.
	time.Sleep(70 * time.Millisecond)
	time.Sleep(67 * time.Millisecond)
	return c, nil
}

func (c *LSM303Minimal) writeRegAccel(reg, val uint8) error {
	return c.accel.WriteReg(uint32(reg), []byte{val})
}

func (c *LSM303Minimal) readReg8Accel(reg uint8) (uint8, error) {
	b, err := c.accel.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// readBurstAccel performs a multi-byte accelerometer transfer: the
// sub-address MSb enables address auto-increment, so reg must be passed
// pre-ORed with 0x80 by the caller for bursts.
func (c *LSM303Minimal) readBurstAccel(reg uint8, n int) ([]byte, error) {
	return c.accel.ReadReg(uint32(reg), n)
}

func (c *LSM303Minimal) writeRegMag(reg, val uint8) error {
	return c.mag.WriteReg(uint32(reg), []byte{val})
}

func (c *LSM303Minimal) readReg8Mag(reg uint8) (uint8, error) {
	b, err := c.mag.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (c *LSM303Minimal) readBurstMag(reg uint8, n int) ([]byte, error) {
	return c.mag.ReadReg(uint32(reg), n)
}

// accelSensitivityG returns the sensitivity in g/LSB at the current mode
// and range.
func (c *LSM303Minimal) accelSensitivityG() float64 {
	return lsm303AccelSensitivityMg[c.mode][c.rangeBits] / 1000.0
}

// convertAccelSample converts a 6-byte accelerometer burst (X, Y, Z, low
// byte first, left-justified) to (x, y, z) signed counts at the current
// resolution.
func (c *LSM303Minimal) convertAccelSample(raw []byte) (int32, int32, int32) {
	rx := connection.ToSigned(uint32(raw[0])|uint32(raw[1])<<8, 16)
	ry := connection.ToSigned(uint32(raw[2])|uint32(raw[3])<<8, 16)
	rz := connection.ToSigned(uint32(raw[4])|uint32(raw[5])<<8, 16)
	shift := lsm303AccelShift[c.mode]
	return rx >> shift, ry >> shift, rz >> shift
}

// convertMagSample converts a 6-byte magnetometer burst (on the wire X, Z,
// Y, each high byte first) to (mx, my, mz) signed counts in X, Y, Z order.
func convertMagSample(raw []byte) (int32, int32, int32) {
	mx := connection.ToSigned(uint32(raw[0])<<8|uint32(raw[1]), 16)
	mz := connection.ToSigned(uint32(raw[2])<<8|uint32(raw[3]), 16)
	my := connection.ToSigned(uint32(raw[4])<<8|uint32(raw[5]), 16)
	return mx, my, mz
}

// ReadAcceleration returns 3-axis linear acceleration as (x, y, z) in *g*.
//
// Burst-reads OUT_X_L_A..OUT_Z_H_A with the auto-increment bit set so all
// six bytes belong to one sample (BDU=1).
func (c *LSM303Minimal) ReadAcceleration() (float32, float32, float32, error) {
	raw, err := c.readBurstAccel(lsm303RegOutXLA|0x80, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	cx, cy, cz := c.convertAccelSample(raw)
	scale := float32(c.accelSensitivityG())
	return float32(cx) * scale, float32(cy) * scale, float32(cz) * scale, nil
}

// ReadMagnetic returns 3-axis magnetic flux density as (x, y, z) in µT.
//
// Burst-reads the six output registers 0x03..0x08 (on the wire X, Z, Y,
// each high byte first) in one transaction and reorders to X, Y, Z.
func (c *LSM303Minimal) ReadMagnetic() (float32, float32, float32, error) {
	raw, err := c.readBurstMag(lsm303RegOutXHM, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	mx, my, mz := convertMagSample(raw)
	gainXY := lsm303MagRanges[c.magGN].gainXY
	gainZ := lsm303MagRanges[c.magGN].gainZ
	return float32(float64(mx) / gainXY * 100.0),
		float32(float64(my) / gainXY * 100.0),
		float32(float64(mz) / gainZ * 100.0), nil
}

// LSM303Full extends LSM303Minimal with complete functionality: accelerometer
// range / data-rate / power-mode configuration, per-axis enable, raw data
// access, the high-pass filter, the 32-level FIFO, two inertial interrupt
// generators with free-fall and 6D/4D orientation, single/double click
// detection, interrupt routing across INT1/INT2, the magnetometer gain /
// data-rate / operating-mode controls, the relative temperature sensor, the
// software compass heading, and memory reboot.
type LSM303Full struct {
	LSM303Minimal

	mu             sync.Mutex
	enabledSources uint16
	callbacks      [3]func(uint16) // pin → callback (index 1..2)
	unsubscribes   [3]func()       // pin → OnEdge unsubscribe closure
	pollPin        *connection.PollingInputPin
	lastStatus     uint16
}

// NewLSM303Full creates an LSM303Full.
//
// accelConn must be bound to the accelerometer slave address 0x19 and
// magConn to the magnetometer slave address 0x1E, on the same bus.
func NewLSM303Full(accelConn, magConn connection.RegisterConnection) (*LSM303Full, error) {
	m, err := NewLSM303Minimal(accelConn, magConn)
	if err != nil {
		return nil, err
	}
	return &LSM303Full{LSM303Minimal: *m}, nil
}

// SetRange sets the accelerometer full-scale range (2, 4, 8 or 16 *g*) and
// updates the cached sensitivity.
func (d *LSM303Full) SetRange(rangeG int) error {
	var code uint8
	switch rangeG {
	case 2:
		code = 0
	case 4:
		code = 1
	case 8:
		code = 2
	case 16:
		code = 3
	default:
		return errors.New("rangeG must be one of 2, 4, 8, 16")
	}
	d.rangeBits = code
	ctrl4, err := d.readReg8Accel(lsm303RegCtrl4A)
	if err != nil {
		return err
	}
	return d.writeRegAccel(lsm303RegCtrl4A, (ctrl4&0xCF)|(code<<4))
}

// SetDataRate sets the accelerometer output data rate in Hz.
//
// rateHz must be one of 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376.
// 1620 Hz and 5376 Hz exist in low-power mode only, 1344 Hz in normal /
// high-resolution mode. There is no 0 Hz entry: use PowerDown for
// power-down.
func (d *LSM303Full) SetDataRate(rateHz int) error {
	var code uint8
	found := false
	for _, entry := range lsm303AccelOdrCodes {
		if entry.hz == rateHz {
			code = entry.code
			found = true
			break
		}
	}
	if !found {
		return errors.New("rateHz must be one of 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376")
	}
	if code == 0x8 && d.mode != 0 {
		return errors.New("1620 Hz requires low-power mode (SetMode(0))")
	}
	if code == 0x9 && rateHz == 5376 && d.mode != 0 {
		return errors.New("5376 Hz requires low-power mode (SetMode(0))")
	}
	if code == 0x9 && rateHz == 1344 && d.mode == 0 {
		return errors.New("1344 Hz requires normal or high-resolution mode")
	}
	ctrl1, err := d.readReg8Accel(lsm303RegCtrl1A)
	if err != nil {
		return err
	}
	if err := d.writeRegAccel(lsm303RegCtrl1A, (ctrl1&0x0F)|(code<<4)); err != nil {
		return err
	}
	d.odrHz = float64(rateHz)
	return nil
}

// SetMode sets the accelerometer operating mode (resolution): 0 = low-power
// (8-bit), 1 = normal (10-bit), 2 = high-resolution (12-bit). Updates the
// cached shift and sensitivity.
func (d *LSM303Full) SetMode(mode int) error {
	if mode < 0 || mode > 2 {
		return errors.New("mode must be 0 (low-power), 1 (normal) or 2 (high-resolution)")
	}
	ctrl1, err := d.readReg8Accel(lsm303RegCtrl1A)
	if err != nil {
		return err
	}
	ctrl4, err := d.readReg8Accel(lsm303RegCtrl4A)
	if err != nil {
		return err
	}
	switch mode {
	case 0:
		ctrl1 |= 0x08  // LPen = 1
		ctrl4 &^= 0x08 // HR = 0
	case 1:
		ctrl1 &^= 0x08
		ctrl4 &^= 0x08
	default:
		ctrl1 &^= 0x08
		ctrl4 |= 0x08
	}
	if err := d.writeRegAccel(lsm303RegCtrl1A, ctrl1); err != nil {
		return err
	}
	if err := d.writeRegAccel(lsm303RegCtrl4A, ctrl4); err != nil {
		return err
	}
	d.mode = uint8(mode)
	return nil
}

// SetAxes enables or disables the accelerometer axes.
func (d *LSM303Full) SetAxes(x, y, z bool) error {
	ctrl1, err := d.readReg8Accel(lsm303RegCtrl1A)
	if err != nil {
		return err
	}
	var axes uint8
	if z {
		axes |= 0x04
	}
	if y {
		axes |= 0x02
	}
	if x {
		axes |= 0x01
	}
	return d.writeRegAccel(lsm303RegCtrl1A, (ctrl1&0xF8)|axes)
}

// PowerDown enters power-down (ODR = 0000). Register contents are retained;
// SetDataRate resumes conversions.
func (d *LSM303Full) PowerDown() error {
	ctrl1, err := d.readReg8Accel(lsm303RegCtrl1A)
	if err != nil {
		return err
	}
	if err := d.writeRegAccel(lsm303RegCtrl1A, ctrl1&0x0F); err != nil {
		return err
	}
	d.odrHz = 0.0
	return nil
}

// ReadAccelerationRaw returns raw accelerometer counts (x, y, z) at the
// current resolution (12/10/8-bit, right-aligned per mode).
func (d *LSM303Full) ReadAccelerationRaw() (int16, int16, int16, error) {
	raw, err := d.readBurstAccel(lsm303RegOutXLA|0x80, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	cx, cy, cz := d.convertAccelSample(raw)
	return int16(cx), int16(cy), int16(cz), nil
}

// NewDataAvailable returns true when STATUS_REG_A.ZYXDA is set.
func (d *LSM303Full) NewDataAvailable() (bool, error) {
	status, err := d.readReg8Accel(lsm303RegStatusA)
	if err != nil {
		return false, err
	}
	return status&0x08 != 0, nil
}

// ReadStatus returns the STATUS_REG_A byte (ZYXOR/ZOR/YOR/XOR overrun,
// ZYXDA/*DA data-ready).
func (d *LSM303Full) ReadStatus() (uint8, error) {
	return d.readReg8Accel(lsm303RegStatusA)
}

// SetHighPass configures the high-pass filter (CTRL_REG2_A).
//
//   - mode: HPM<1:0> — 0 normal (reset by reading REFERENCE_A), 1 reference
//     signal for filtering, 2 normal, 3 autoreset on interrupt event.
//   - cutoff: HPCF<2:1> raw code 0–3 (no Hz values tabulated for this part).
//   - filterOutput: true = filtered data goes to the output registers and FIFO.
//   - onIA1 / onIA2: high-pass filter enabled for interrupt generator 1 / 2.
//   - onClick: high-pass filter enabled for click detection.
func (d *LSM303Full) SetHighPass(mode, cutoff int, filterOutput, onIA1, onIA2, onClick bool) error {
	ctrl2 := (uint8(mode&0x03) << 6) | (uint8(cutoff&0x03) << 4)
	if filterOutput {
		ctrl2 |= 0x08
	}
	if onClick {
		ctrl2 |= 0x04
	}
	if onIA2 {
		ctrl2 |= 0x02
	}
	if onIA1 {
		ctrl2 |= 0x01
	}
	return d.writeRegAccel(lsm303RegCtrl2A, ctrl2)
}

// ResetHighPass resets the high-pass filter (HPM = 00) by reading
// REFERENCE_A, and returns the REFERENCE_A register value.
func (d *LSM303Full) ResetHighPass() (uint8, error) {
	return d.readReg8Accel(lsm303RegReferenceA)
}

// SetReference writes the high-pass reference register (0–255), used for
// HPM = 01.
func (d *LSM303Full) SetReference(value int) error {
	return d.writeRegAccel(lsm303RegReferenceA, uint8(value)&0xFF)
}

// SetFifo configures the 32-level FIFO.
//
//   - mode: 0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO).
//     Returning to bypass clears the buffer.
//   - watermark: watermark level 0–31.
//   - triggerInt2: trigger-mode source — false = INT1, true = INT2.
func (d *LSM303Full) SetFifo(mode, watermark int, triggerInt2 bool) error {
	fifoCtrl := (uint8(mode&0x03) << 6) | (uint8(watermark) & 0x1F)
	if triggerInt2 {
		fifoCtrl |= 0x20
	}
	if err := d.writeRegAccel(lsm303RegFifoCtrlA, fifoCtrl); err != nil {
		return err
	}
	ctrl5, err := d.readReg8Accel(lsm303RegCtrl5A)
	if err != nil {
		return err
	}
	if mode >= 1 && mode <= 3 {
		ctrl5 |= 0x40
	} else {
		ctrl5 &^= 0x40
	}
	return d.writeRegAccel(lsm303RegCtrl5A, ctrl5)
}

// FifoLevel returns the number of unread sample sets in the FIFO (0–32),
// FIFO_SRC_REG_A.FSS<4:0>.
func (d *LSM303Full) FifoLevel() (uint8, error) {
	src, err := d.readReg8Accel(lsm303RegFifoSrcA)
	if err != nil {
		return 0, err
	}
	return src & 0x1F, nil
}

// ReadFifo drains up to count sample sets from the FIFO as (x, y, z) tuples
// in *g*; each sample is popped with one 6-byte burst read. count <= 0
// reads all unread samples reported by FSS.
func (d *LSM303Full) ReadFifo(count int) ([][3]float32, error) {
	if count <= 0 {
		level, err := d.FifoLevel()
		if err != nil {
			return nil, err
		}
		count = int(level)
	}
	out := make([][3]float32, 0, count)
	scale := float32(d.accelSensitivityG())
	for i := 0; i < count; i++ {
		raw, err := d.readBurstAccel(lsm303RegOutXLA|0x80, 6)
		if err != nil {
			return nil, err
		}
		cx, cy, cz := d.convertAccelSample(raw)
		out = append(out, [3]float32{float32(cx) * scale, float32(cy) * scale, float32(cz) * scale})
	}
	return out, nil
}

// ReadFifoStatus returns the decoded FIFO status: (watermark, overrun,
// empty, level) — the first three are flags, level is the FSS<4:0> sample
// count.
func (d *LSM303Full) ReadFifoStatus() (watermark, overrun, empty bool, level uint8, err error) {
	src, err := d.readReg8Accel(lsm303RegFifoSrcA)
	if err != nil {
		return false, false, false, 0, err
	}
	return src&0x80 != 0, src&0x40 != 0, src&0x20 != 0, src & 0x1F, nil
}

// intRegs returns the (CFG, SRC, THS, DUR) register quad for generator 1
// or 2.
func intRegs(generator int) (uint8, uint8, uint8, uint8, error) {
	switch generator {
	case 1:
		return lsm303RegInt1CfgA, lsm303RegInt1SrcA, lsm303RegInt1ThsA, lsm303RegInt1DurA, nil
	case 2:
		return lsm303RegInt2CfgA, lsm303RegInt2SrcA, lsm303RegInt2ThsA, lsm303RegInt2DurA, nil
	default:
		return 0, 0, 0, 0, errors.New("generator must be 1 or 2")
	}
}

// encodeThreshold converts a threshold in *g* to the 7-bit INTx_THS /
// CLICK_THS code (1 LSb = full-scale / 128).
func (d *LSM303Full) encodeThreshold(thresholdG float32) uint8 {
	fsTable := [4]float64{2, 4, 8, 16}
	code := int32(math.Round(float64(thresholdG) * 128.0 / fsTable[d.rangeBits]))
	if code > 127 {
		code = 127
	}
	if code < 0 {
		code = 0
	}
	return uint8(code)
}

// encodeDuration converts a duration in ms to the 1/ODR code, clamped to
// maxBits (7 for INTx_DURATION_A / TIME_LIMIT_A, 8 for TIME_LATENCY_A /
// TIME_WINDOW_A).
func (d *LSM303Full) encodeDuration(durationMs float32, maxBits uint) uint8 {
	code := int32(math.Round(float64(durationMs) * d.odrHz / 1000.0))
	limit := int32((1 << maxBits) - 1)
	if code > limit {
		code = limit
	}
	if code < 0 {
		code = 0
	}
	return uint8(code)
}

// SetMotionInterrupt configures one inertial interrupt generator.
//
//   - generator: 1 or 2 (INT1 generator / INT2 generator).
//   - events: 6-bit event mask (bit 5 = Z high, 4 = Z low, 3 = Y high,
//     2 = Y low, 1 = X high, 0 = X low; the same bits select the direction
//     zones in 6D modes).
//   - thresholdG: event threshold in *g* (1 LSb = full-scale / 128).
//   - durationMs: minimum event duration in ms (1 LSb = 1/ODR).
//   - andMode: true = AND combination of the enabled events, false = OR.
func (d *LSM303Full) SetMotionInterrupt(generator, events int, thresholdG, durationMs float32, andMode bool) error {
	cfg, _, ths, dur, err := intRegs(generator)
	if err != nil {
		return err
	}
	var cfgVal uint8
	if andMode {
		cfgVal = 0x80
	}
	cfgVal |= uint8(events) & 0x3F
	if err := d.writeRegAccel(cfg, cfgVal); err != nil {
		return err
	}
	if err := d.writeRegAccel(ths, d.encodeThreshold(thresholdG)); err != nil {
		return err
	}
	return d.writeRegAccel(dur, d.encodeDuration(durationMs, 7))
}

// SetFreeFall configures free-fall detection on one generator: all three
// axes' low events, AND-combined (INTx_CFG_A = 0x95). Defaults: generator
// 1, threshold 0.35 *g*, duration 30 ms.
func (d *LSM303Full) SetFreeFall(generator int, thresholdG, durationMs float32) error {
	return d.SetMotionInterrupt(generator, 0x15, thresholdG, durationMs, true)
}

// SetOrientation configures 6D / 4D orientation recognition on one
// generator. position=false = 6D movement recognition (one-ODR pulse when
// the orientation moves into a known zone), true = 6D position recognition
// (stays active inside a known zone). fourD = 4D detection (Z-axis zone
// disabled; needs the 6D bit).
func (d *LSM303Full) SetOrientation(generator int, position, fourD bool) error {
	cfg, _, _, _, err := intRegs(generator)
	if err != nil {
		return err
	}
	value := uint8(0x40 | 0x3F) // 6D + all six direction zones
	if position {
		value |= 0x80
	}
	if err := d.writeRegAccel(cfg, value); err != nil {
		return err
	}
	ctrl5, err := d.readReg8Accel(lsm303RegCtrl5A)
	if err != nil {
		return err
	}
	if generator == 1 {
		if fourD {
			ctrl5 |= 0x04
		} else {
			ctrl5 &^= 0x04
		}
	} else {
		if fourD {
			ctrl5 |= 0x01
		} else {
			ctrl5 &^= 0x01
		}
	}
	return d.writeRegAccel(lsm303RegCtrl5A, ctrl5)
}

// SetLatch latches (or unlatches) one generator's interrupt until its
// source register is read.
func (d *LSM303Full) SetLatch(generator int, enabled bool) error {
	ctrl5, err := d.readReg8Accel(lsm303RegCtrl5A)
	if err != nil {
		return err
	}
	if generator == 1 {
		if enabled {
			ctrl5 |= 0x08
		} else {
			ctrl5 &^= 0x08
		}
	} else if generator == 2 {
		if enabled {
			ctrl5 |= 0x02
		} else {
			ctrl5 &^= 0x02
		}
	} else {
		return errors.New("generator must be 1 or 2")
	}
	return d.writeRegAccel(lsm303RegCtrl5A, ctrl5)
}

// ReadInterruptSource reads one generator's source register — clears the
// interrupt.
func (d *LSM303Full) ReadInterruptSource(generator int) (uint8, error) {
	_, src, _, _, err := intRegs(generator)
	if err != nil {
		return 0, err
	}
	return d.readReg8Accel(src)
}

// SetClick configures single / double click detection.
//
//   - single / double: axis masks (X=1, Y=2, Z=4; 0 = off).
//   - thresholdG: click threshold in *g* (1 LSb = full-scale / 128).
//   - timeLimitMs: maximum click duration in ms (1 LSb = 1/ODR, 7-bit).
//   - latencyMs: time after a click in which the click interrupt is not
//     re-armed (1 LSb = 1/ODR, 8-bit).
//   - windowMs: time window for a second click (1 LSb = 1/ODR, 8-bit).
func (d *LSM303Full) SetClick(single, double uint8, thresholdG, timeLimitMs, latencyMs, windowMs float32) error {
	if single > 7 || double > 7 {
		return errors.New("single / double are axis masks: X=1, Y=2, Z=4")
	}
	// Axis masks X=1/Y=2/Z=4 spread to CLICK_CFG_A bits:
	// XD=0, XS=1, YD=2, YS=3, ZD=4, ZS=5.
	singleCode := ((single & 0x01) << 1) | ((single & 0x02) << 2) | ((single & 0x04) << 3)
	doubleCode := (double & 0x01) | ((double & 0x02) << 1) | ((double & 0x04) << 2)
	if err := d.writeRegAccel(lsm303RegClickCfgA, singleCode|doubleCode); err != nil {
		return err
	}
	if err := d.writeRegAccel(lsm303RegClickThsA, d.encodeThreshold(thresholdG)); err != nil {
		return err
	}
	if err := d.writeRegAccel(lsm303RegTimeLimitA, d.encodeDuration(timeLimitMs, 7)); err != nil {
		return err
	}
	if err := d.writeRegAccel(lsm303RegTimeLatA, d.encodeDuration(latencyMs, 8)); err != nil {
		return err
	}
	return d.writeRegAccel(lsm303RegTimeWinA, d.encodeDuration(windowMs, 8))
}

// ReadClickSource reads the click source register — clears the click
// interrupt.
func (d *LSM303Full) ReadClickSource() (uint8, error) {
	return d.readReg8Accel(lsm303RegClickSrcA)
}

// SetInterruptPolarity sets the INT1 / INT2 output polarity: true = INT
// pins active-low, false = active-high (default).
func (d *LSM303Full) SetInterruptPolarity(activeLow bool) error {
	ctrl6, err := d.readReg8Accel(lsm303RegCtrl6A)
	if err != nil {
		return err
	}
	if activeLow {
		ctrl6 |= 0x02
	} else {
		ctrl6 &^= 0x02
	}
	return d.writeRegAccel(lsm303RegCtrl6A, ctrl6)
}

// SetMagRange sets the magnetometer full-scale range (gain) in µT — one of
// 130, 190, 250, 400, 470, 560, 810 — and updates the cached conversion
// gains.
func (d *LSM303Full) SetMagRange(rangeUT int) error {
	var index uint8
	found := false
	for i, entry := range lsm303MagRanges {
		if entry.fullScaleUT == rangeUT {
			index = uint8(i)
			found = true
			break
		}
	}
	if !found {
		return errors.New("rangeUT must be one of 130, 190, 250, 400, 470, 560, 810")
	}
	d.magGN = index
	return d.writeRegMag(lsm303RegCrbM, (index+1)<<5)
}

// SetMagDataRate sets the magnetometer output data rate in Hz — one of
// 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220. Preserves the TEMP_EN setting.
func (d *LSM303Full) SetMagDataRate(rateHz float64) error {
	var code uint8
	found := false
	for _, entry := range lsm303MagOdrCodes {
		if entry.hz == rateHz {
			code = entry.code
			found = true
			break
		}
	}
	if !found {
		return errors.New("rateHz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220")
	}
	cra, err := d.readReg8Mag(lsm303RegCraM)
	if err != nil {
		return err
	}
	if err := d.writeRegMag(lsm303RegCraM, (cra&0x80)|(code<<2)); err != nil {
		return err
	}
	d.magOdrHz = rateHz
	return nil
}

// SetMagMode sets the magnetometer operating mode: 0 continuous conversion,
// 1 single conversion (one conversion, then back to sleep), 2 sleep.
func (d *LSM303Full) SetMagMode(mode int) error {
	if mode < 0 || mode > 2 {
		return errors.New("mode must be 0 (continuous), 1 (single) or 2 (sleep)")
	}
	return d.writeRegMag(lsm303RegMrM, uint8(mode))
}

// ReadMagneticRaw returns raw magnetometer counts (x, y, z) — signed 12-bit
// values in X, Y, Z order, after reordering.
func (d *LSM303Full) ReadMagneticRaw() (int16, int16, int16, error) {
	raw, err := d.readBurstMag(lsm303RegOutXHM, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	mx, my, mz := convertMagSample(raw)
	return int16(mx), int16(my), int16(mz), nil
}

// MagDataReady returns true when SR_REG_M.DRDY is set.
func (d *LSM303Full) MagDataReady() (bool, error) {
	sr, err := d.readReg8Mag(lsm303RegSrM)
	if err != nil {
		return false, err
	}
	return sr&0x01 != 0, nil
}

// ReadMagStatus returns the decoded magnetometer status register: (drdy,
// lock) — DRDY = new set of measurements available, LOCK = output registers
// locked mid-read.
func (d *LSM303Full) ReadMagStatus() (drdy, lock bool, err error) {
	sr, err := d.readReg8Mag(lsm303RegSrM)
	if err != nil {
		return false, false, err
	}
	return sr&0x01 != 0, sr&0x02 != 0, nil
}

// ReadMagIdentity reads the magnetometer identification registers
// IRA/IRB/IRC (0x48 0x34 0x33 — identical to HMC5883L).
func (d *LSM303Full) ReadMagIdentity() ([3]byte, error) {
	id, err := d.readBurstMag(lsm303RegIraM, 3)
	if err != nil {
		return [3]byte{}, err
	}
	return [3]byte{id[0], id[1], id[2]}, nil
}

// EnableTemperature enables or disables the relative temperature sensor
// (TEMP_EN in CRA_REG_M; updates at the magnetometer data rate).
func (d *LSM303Full) EnableTemperature(enabled bool) error {
	cra, err := d.readReg8Mag(lsm303RegCraM)
	if err != nil {
		return err
	}
	if enabled {
		cra |= 0x80
	} else {
		cra &^= 0x80
	}
	if err := d.writeRegMag(lsm303RegCraM, cra); err != nil {
		return err
	}
	d.tempEnabled = enabled
	return nil
}

// ReadTemperatureDelta returns the relative temperature in °C (8 LSB/°C).
//
// The sensor is relative only — the datasheet gives no zero-degree
// reference — so the value is a delta, never an absolute °C. Enables
// TEMP_EN on first use (read-modify-write).
func (d *LSM303Full) ReadTemperatureDelta() (float32, error) {
	if !d.tempEnabled {
		if err := d.EnableTemperature(true); err != nil {
			return 0, err
		}
	}
	raw, err := d.readBurstMag(lsm303RegTempHM, 2)
	if err != nil {
		return 0, err
	}
	value := connection.ToSigned(uint32(raw[0])<<8|uint32(raw[1]), 16) >> 4
	return float32(float64(value) / 8.0), nil
}

// Heading computes the compass heading from both sensors, in degrees
// (0–360, atan2(y, x) convention).
//
// tiltCompensate=true (the normal use) projects the magnetic field onto the
// horizontal plane using the current acceleration; false = flat compass
// from the magnetic X/Y components only. The datasheet documents neither
// the axis alignment between the two dies nor a declination, so the result
// assumes both sensors report in the same body frame and is magnetic (not
// true) north.
func (d *LSM303Full) Heading(tiltCompensate bool) (float64, error) {
	mx, my, mz, err := d.ReadMagnetic()
	if err != nil {
		return 0, err
	}
	var angle float64
	if !tiltCompensate {
		angle = math.Atan2(float64(my), float64(mx)) * 180.0 / math.Pi
	} else {
		ax, ay, az, err := d.ReadAcceleration()
		if err != nil {
			return 0, err
		}
		norm := math.Sqrt(float64(ax*ax + ay*ay + az*az))
		if norm == 0.0 {
			norm = 1.0
		}
		axn := float64(ax) / norm
		ayn := float64(ay) / norm
		azn := float64(az) / norm
		roll := math.Atan2(ayn, azn)
		pitch := math.Atan2(-axn, ayn*math.Sin(roll)+azn*math.Cos(roll))
		xh := float64(mx)*math.Cos(pitch) + float64(mz)*math.Sin(pitch)
		yh := float64(mx)*math.Sin(roll)*math.Sin(pitch) + float64(my)*math.Cos(roll) -
			float64(mz)*math.Sin(roll)*math.Cos(pitch)
		angle = math.Atan2(yh, xh) * 180.0 / math.Pi
	}
	if angle >= 0.0 {
		return angle, nil
	}
	return angle + 360.0, nil
}

// EnableInterrupt routes one interrupt source to INT1 (pin=1) or INT2
// (pin=2). Only routes the source; the generator or function behind it is
// configured with SetMotionInterrupt / SetClick / .... pin is ignored for
// INT1-only / INT2-only sources. SourceMagDataReady is rejected (it is
// always on the dedicated magnetometer DRDY pin and is not routable).
func (d *LSM303Full) EnableInterrupt(source uint16, pin int) error {
	if source == SourceMagDataReady {
		return errors.New("SourceMagDataReady is on the magnetometer DRDY pin and is not routable")
	}
	if pin != 1 && pin != 2 {
		return errors.New("pin must be 1 or 2")
	}
	ctrl3, err := d.readReg8Accel(lsm303RegCtrl3A)
	if err != nil {
		return err
	}
	ctrl6, err := d.readReg8Accel(lsm303RegCtrl6A)
	if err != nil {
		return err
	}
	switch source {
	case SourceDataReady:
		ctrl3 |= lsm303I1Drdy1
	case SourceWatermark:
		ctrl3 |= lsm303I1Wtm
	case SourceOverrun:
		ctrl3 |= lsm303I1Overrun
	case SourceIA1:
		if pin == 1 {
			ctrl3 |= lsm303I1Aoi1
		} else {
			ctrl6 |= lsm303I2Int1
		}
	case SourceIA2:
		if pin == 1 {
			ctrl3 |= lsm303I1Aoi2
		} else {
			ctrl6 |= lsm303I2Int2
		}
	case SourceClick:
		if pin == 1 {
			ctrl3 |= lsm303I1Click
		} else {
			ctrl6 |= lsm303I2Click
		}
	case SourceBoot:
		if pin != 2 {
			return errors.New("SourceBoot is INT2-only")
		}
		ctrl6 |= lsm303BootI1
	default:
		return fmt.Errorf("unknown interrupt source 0x%X", source)
	}
	if err := d.writeRegAccel(lsm303RegCtrl3A, ctrl3); err != nil {
		return err
	}
	if err := d.writeRegAccel(lsm303RegCtrl6A, ctrl6); err != nil {
		return err
	}
	d.mu.Lock()
	d.enabledSources |= source
	d.mu.Unlock()
	return nil
}

// DisableInterrupt removes one interrupt source from INT1 (pin=1) or INT2
// (pin=2).
func (d *LSM303Full) DisableInterrupt(source uint16, pin int) error {
	if source == SourceMagDataReady {
		return errors.New("SourceMagDataReady is on the magnetometer DRDY pin and is not routable")
	}
	if pin != 1 && pin != 2 {
		return errors.New("pin must be 1 or 2")
	}
	ctrl3, err := d.readReg8Accel(lsm303RegCtrl3A)
	if err != nil {
		return err
	}
	ctrl6, err := d.readReg8Accel(lsm303RegCtrl6A)
	if err != nil {
		return err
	}
	switch source {
	case SourceDataReady:
		ctrl3 &^= lsm303I1Drdy1
	case SourceWatermark:
		ctrl3 &^= lsm303I1Wtm
	case SourceOverrun:
		ctrl3 &^= lsm303I1Overrun
	case SourceIA1:
		if pin == 1 {
			ctrl3 &^= lsm303I1Aoi1
		} else {
			ctrl6 &^= lsm303I2Int1
		}
	case SourceIA2:
		if pin == 1 {
			ctrl3 &^= lsm303I1Aoi2
		} else {
			ctrl6 &^= lsm303I2Int2
		}
	case SourceClick:
		if pin == 1 {
			ctrl3 &^= lsm303I1Click
		} else {
			ctrl6 &^= lsm303I2Click
		}
	case SourceBoot:
		if pin != 2 {
			return errors.New("SourceBoot is INT2-only")
		}
		ctrl6 &^= lsm303BootI1
	default:
		return fmt.Errorf("unknown interrupt source 0x%X", source)
	}
	if err := d.writeRegAccel(lsm303RegCtrl3A, ctrl3); err != nil {
		return err
	}
	if err := d.writeRegAccel(lsm303RegCtrl6A, ctrl6); err != nil {
		return err
	}
	d.mu.Lock()
	d.enabledSources &^= source
	d.mu.Unlock()
	return nil
}

// PollInterrupt reads the source registers of every currently enabled
// interrupt source and returns the combined status: bit 0 SourceDataReady,
// bit 1 SourceWatermark, bit 2 SourceOverrun, bit 3 SourceIA1, bit 4
// SourceIA2, bit 5 SourceClick, bit 7 SourceMagDataReady. SourceBoot is
// pin-only and never reported. Reading INTx_SOURCE_A / CLICK_SRC_A clears
// their latched flags, so a poll acknowledges the INT line.
func (d *LSM303Full) PollInterrupt() (uint16, error) {
	d.mu.Lock()
	enabled := d.enabledSources
	d.mu.Unlock()

	var status uint16
	if enabled&SourceDataReady != 0 {
		s, err := d.readReg8Accel(lsm303RegStatusA)
		if err != nil {
			return 0, err
		}
		if s&0x08 != 0 {
			status |= SourceDataReady
		}
	}
	if enabled&(SourceWatermark|SourceOverrun) != 0 {
		src, err := d.readReg8Accel(lsm303RegFifoSrcA)
		if err != nil {
			return 0, err
		}
		if src&0x80 != 0 {
			status |= SourceWatermark
		}
		if src&0x40 != 0 {
			status |= SourceOverrun
		}
	}
	if enabled&SourceIA1 != 0 {
		src, err := d.readReg8Accel(lsm303RegInt1SrcA)
		if err != nil {
			return 0, err
		}
		if src&0x40 != 0 {
			status |= SourceIA1
		}
	}
	if enabled&SourceIA2 != 0 {
		src, err := d.readReg8Accel(lsm303RegInt2SrcA)
		if err != nil {
			return 0, err
		}
		if src&0x40 != 0 {
			status |= SourceIA2
		}
	}
	if enabled&SourceClick != 0 {
		src, err := d.readReg8Accel(lsm303RegClickSrcA)
		if err != nil {
			return 0, err
		}
		if src&0x40 != 0 {
			status |= SourceClick
		}
	}
	// Magnetometer DRDY is always polled (the connection is required at
	// construction and never nil).
	sr, err := d.readReg8Mag(lsm303RegSrM)
	if err != nil {
		return 0, err
	}
	if sr&0x01 != 0 {
		status |= SourceMagDataReady
	}
	return status, nil
}

// OnInterrupt subscribes callback to INT1 (pin=1) or INT2 (pin=2)
// assertions; it receives the combined PollInterrupt status.
//
// Uses the accelerometer connection's IntPin when wired (both INT lines
// typically share one edge input); falls back to a 5 ms polling goroutine
// otherwise. With a real INT edge every event fires the callback; the
// polling fallback only reports changes of the status mask.
func (d *LSM303Full) OnInterrupt(callback func(status uint16), pin int) error {
	if pin != 1 && pin != 2 {
		return errors.New("pin must be 1 or 2")
	}
	if callback == nil {
		return errors.New("callback must not be nil")
	}
	d.mu.Lock()
	if d.unsubscribes[pin] != nil {
		d.unsubscribes[pin]()
		d.unsubscribes[pin] = nil
	}
	d.callbacks[pin] = callback
	d.mu.Unlock()

	inputPin := d.accel.IntPin()
	if inputPin == nil {
		d.mu.Lock()
		if d.pollPin == nil {
			d.pollPin = connection.NewDefaultPollingInputPin()
		}
		d.mu.Unlock()
		inputPin = d.pollPin
	}
	unsubscribe := inputPin.OnEdge(connection.Falling, func() { d.handleEdge(pin) })
	d.mu.Lock()
	d.unsubscribes[pin] = unsubscribe
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes from INT1 (pin=1) or INT2 (pin=2) assertions
// and stops the polling fallback once no callback remains.
func (d *LSM303Full) OffInterrupt(pin int) error {
	if pin != 1 && pin != 2 {
		return errors.New("pin must be 1 or 2")
	}
	d.mu.Lock()
	d.callbacks[pin] = nil
	unsubscribe := d.unsubscribes[pin]
	d.unsubscribes[pin] = nil
	poll := d.pollPin
	done := d.callbacks[1] == nil && d.callbacks[2] == nil
	d.mu.Unlock()

	if unsubscribe != nil {
		unsubscribe()
	}
	if done && poll != nil {
		d.mu.Lock()
		d.pollPin = nil
		d.mu.Unlock()
		return poll.Close()
	}
	return nil
}

// handleEdge polls the source registers and dispatches the combined status
// to the pin's callback.
func (d *LSM303Full) handleEdge(pin int) {
	status, err := d.PollInterrupt()
	if err != nil {
		return
	}
	d.mu.Lock()
	callback := d.callbacks[pin]
	// A real INT edge is always an event; the polling fallback ticks
	// continuously, so it only reports changes of the status mask.
	fire := d.pollPin == nil || status != d.lastStatus
	d.lastStatus = status
	d.mu.Unlock()
	if fire && callback != nil {
		callback(status)
	}
}

// Reboot reboots the accelerometer memory content (CTRL_REG5_A BOOT = 1).
//
// Reloads the factory trim values; waits ≥ 5 ms. The datasheet does not say
// whether the control registers survive, so re-run the initialisation
// (construct a fresh driver) afterwards.
func (d *LSM303Full) Reboot() error {
	ctrl5, err := d.readReg8Accel(lsm303RegCtrl5A)
	if err != nil {
		return err
	}
	if err := d.writeRegAccel(lsm303RegCtrl5A, ctrl5|0x80); err != nil {
		return err
	}
	time.Sleep(5 * time.Millisecond)
	return nil
}

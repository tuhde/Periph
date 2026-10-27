package accelerometer

import (
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Accelerometer (I²C address 0x19) register addresses. Multi-byte reads set
// bit 7 of the sub-address (auto-increment); see accelReadBurst.
const (
	lsm303RegCtrl1A       uint32 = 0x20
	lsm303RegCtrl2A       uint32 = 0x21
	lsm303RegCtrl3A       uint32 = 0x22
	lsm303RegCtrl4A       uint32 = 0x23
	lsm303RegCtrl5A       uint32 = 0x24
	lsm303RegCtrl6A       uint32 = 0x25
	lsm303RegReferenceA   uint32 = 0x26
	lsm303RegStatusA      uint32 = 0x27
	lsm303RegOutXLA       uint32 = 0x28
	lsm303RegFifoCtrlA    uint32 = 0x2E
	lsm303RegFifoSrcA     uint32 = 0x2F
	lsm303RegInt1CfgA     uint32 = 0x30
	lsm303RegInt1SrcA     uint32 = 0x31
	lsm303RegInt1ThsA     uint32 = 0x32
	lsm303RegInt1DurA     uint32 = 0x33
	lsm303RegInt2CfgA     uint32 = 0x34
	lsm303RegInt2SrcA     uint32 = 0x35
	lsm303RegInt2ThsA     uint32 = 0x36
	lsm303RegInt2DurA     uint32 = 0x37
	lsm303RegClickCfgA    uint32 = 0x38
	lsm303RegClickSrcA    uint32 = 0x39
	lsm303RegClickThsA    uint32 = 0x3A
	lsm303RegTimeLimitA   uint32 = 0x3B
	lsm303RegTimeLatencyA uint32 = 0x3C
	lsm303RegTimeWindowA  uint32 = 0x3D
)

// Magnetometer (I²C address 0x1E) register addresses. The magnetometer has an
// address pointer with no auto-increment bit.
const (
	lsm303RegCraM      uint32 = 0x00
	lsm303RegCrbM      uint32 = 0x01
	lsm303RegMrM       uint32 = 0x02
	lsm303RegOutXHM    uint32 = 0x03
	lsm303RegSrM       uint32 = 0x09
	lsm303RegIraM      uint32 = 0x0A
	lsm303RegTempOutHM uint32 = 0x31
	lsm303RegTempOutLM uint32 = 0x32
)

// lsm303AutoIncrement is OR-ed into the accelerometer sub-address for
// multi-byte transfers (SUB bit 7 enables address auto-increment).
const lsm303AutoIncrement uint32 = 0x80

// lsm303MagIdentity is the magnetometer identification sequence IRA/IRB/IRC
// (ASCII "H43"). It is identical to the HMC5883L identity.
var lsm303MagIdentity = [3]byte{0x48, 0x34, 0x33}

// lsm303AccelHRMgPerLSB is the high-resolution sensitivity in mg/LSB, indexed
// by the FS<1:0> code (±2, ±4, ±8, ±16 g). Normal and low-power sensitivities
// are derived by multiplying by 4 and 16 (see Data Conversion in the spec).
var lsm303AccelHRMgPerLSB = [4]float32{1, 2, 4, 12}

// lsm303AccelFullScaleG maps the FS<1:0> code to full-scale range in g.
var lsm303AccelFullScaleG = [4]float32{2, 4, 8, 16}

// lsm303MagGain is one magnetometer gain setting: the full-scale range in µT,
// the CRB_REG_M GN<2:0> code, and the X/Y and Z gains in LSB per gauss.
type lsm303MagGain struct {
	rangeUT int
	code    uint8
	xyGain  float32
	zGain   float32
}

// lsm303MagGains lists the seven defined gain settings (GN = 001 … 111).
var lsm303MagGains = []lsm303MagGain{
	{130, 1, 1100, 980},
	{190, 2, 855, 760},
	{250, 3, 670, 600},
	{400, 4, 450, 400},
	{470, 5, 400, 355},
	{560, 6, 330, 295},
	{810, 7, 230, 205},
}

// LSM303SourceDataReady is the new accelerometer XYZ sample (STATUS_REG_A.ZYXDA). Routed to INT1.
const LSM303SourceDataReady uint16 = 0x0001

// LSM303SourceWatermark is the FIFO content exceeding the watermark (FIFO_SRC_REG_A.WTM). Routed to INT1.
const LSM303SourceWatermark uint16 = 0x0002

// LSM303SourceOverrun is a FIFO overrun (FIFO_SRC_REG_A.OVRN_FIFO). Routed to INT1.
const LSM303SourceOverrun uint16 = 0x0004

// LSM303SourceIA1 is an inertial generator 1 event (INT1_SOURCE_A.IA). Routed to INT1 or INT2.
const LSM303SourceIA1 uint16 = 0x0008

// LSM303SourceIA2 is an inertial generator 2 event (INT2_SOURCE_A.IA). Routed to INT1 or INT2.
const LSM303SourceIA2 uint16 = 0x0010

// LSM303SourceClick is a single or double click (CLICK_SRC_A.IA). Routed to INT1 or INT2.
const LSM303SourceClick uint16 = 0x0020

// LSM303SourceBoot is the boot procedure running. Routed to INT2 only and never reported by PollInterrupt.
const LSM303SourceBoot uint16 = 0x0040

// LSM303SourceMagDataReady is a new magnetometer sample (SR_REG_M.DRDY). It is on the dedicated DRDY pin and cannot be routed.
const LSM303SourceMagDataReady uint16 = 0x0080

// LSM303Minimal is the LSM303DLHC accelerometer and magnetometer, minimal interface.
//
// The LSM303DLHC is two I²C slaves: the accelerometer at 0x19 and the
// magnetometer at 0x1E. Both are addressed through RegisterConnection with
// 1-byte register addresses. Reads acceleration in g and magnetic flux
// density in µT with the defaults applied at construction: ±2 g, 100 Hz,
// high-resolution mode; ±130 µT, 15 Hz, continuous conversion.
type LSM303Minimal struct {
	accel connection.RegisterConnection
	mag   connection.RegisterConnection

	// bus serializes single bus transactions against the interrupt handler.
	bus sync.Mutex

	fsCode      uint8         // accelerometer FS<1:0> code (0 = ±2 g)
	mode        int           // accelerometer mode: 0 low-power, 1 normal, 2 high-resolution
	odrHz       int           // accelerometer output data rate in Hz (0 = power-down)
	magGain     lsm303MagGain // magnetometer gain setting in use
	tempEnabled bool          // TEMP_EN has been set in CRA_REG_M
}

// NewLSM303Minimal creates an LSM303Minimal and runs the initialization sequence.
//
// accelConn must be a RegisterConnection bound to the accelerometer at I²C
// address 0x19; magConn must be bound to the magnetometer at 0x1E. Both must
// be on the same bus. The magnetometer identity (0x48 0x34 0x33) is checked
// and an error is returned on mismatch. The function sleeps about 75 ms while
// the sensors start their first conversions.
func NewLSM303Minimal(accelConn, magConn connection.RegisterConnection) (*LSM303Minimal, error) {
	c := &LSM303Minimal{
		accel:   accelConn,
		mag:     magConn,
		fsCode:  0,
		mode:    2,
		odrHz:   100,
		magGain: lsm303MagGains[0],
	}
	time.Sleep(5 * time.Millisecond)

	id, err := c.magRead(lsm303RegIraM, 3)
	if err != nil {
		return nil, err
	}
	if id[0] != lsm303MagIdentity[0] || id[1] != lsm303MagIdentity[1] || id[2] != lsm303MagIdentity[2] {
		return nil, fmt.Errorf("LSM303 magnetometer ID: expected 48 34 33, got %02X %02X %02X",
			id[0], id[1], id[2])
	}

	for _, reg := range []uint32{lsm303RegCtrl2A, lsm303RegCtrl3A, lsm303RegCtrl5A, lsm303RegCtrl6A, lsm303RegFifoCtrlA} {
		if err := c.accelWrite(reg, 0x00); err != nil {
			return nil, err
		}
	}
	// BDU = 1, FS = ±2 g, HR = 1.
	if err := c.accelWrite(lsm303RegCtrl4A, 0x88); err != nil {
		return nil, err
	}
	// ODR = 100 Hz, LPen = 0, X/Y/Z enabled.
	if err := c.accelWrite(lsm303RegCtrl1A, 0x57); err != nil {
		return nil, err
	}

	if err := c.magWrite(lsm303RegCraM, 0x10); err != nil { // 15 Hz, temperature off
		return nil, err
	}
	if err := c.magWrite(lsm303RegCrbM, 0x20); err != nil { // ±1.3 gauss
		return nil, err
	}
	if err := c.magWrite(lsm303RegMrM, 0x00); err != nil { // continuous conversion
		return nil, err
	}

	// Covers the accelerometer turn-on (7/ODR = 70 ms at 100 Hz) and the
	// first magnetometer sample (1/15 Hz ≈ 67 ms).
	time.Sleep(70 * time.Millisecond)
	return c, nil
}

// accelWrite writes one accelerometer register (single access, no auto-increment).
func (c *LSM303Minimal) accelWrite(reg uint32, value uint8) error {
	c.bus.Lock()
	defer c.bus.Unlock()
	return c.accel.WriteReg(reg, []byte{value})
}

// accelRead reads one accelerometer register (single access, no auto-increment).
func (c *LSM303Minimal) accelRead(reg uint32) (uint8, error) {
	c.bus.Lock()
	defer c.bus.Unlock()
	b, err := c.accel.ReadReg(reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// accelReadBurst reads n accelerometer registers starting at reg with the
// auto-increment bit set in the sub-address.
func (c *LSM303Minimal) accelReadBurst(reg uint32, n int) ([]byte, error) {
	c.bus.Lock()
	defer c.bus.Unlock()
	return c.accel.ReadReg(reg|lsm303AutoIncrement, n)
}

// accelUpdate performs a read-modify-write of the bits in mask.
func (c *LSM303Minimal) accelUpdate(reg uint32, mask, value uint8) error {
	old, err := c.accelRead(reg)
	if err != nil {
		return err
	}
	return c.accelWrite(reg, (old&^mask)|(value&mask))
}

// magWrite writes one magnetometer register.
func (c *LSM303Minimal) magWrite(reg uint32, value uint8) error {
	c.bus.Lock()
	defer c.bus.Unlock()
	return c.mag.WriteReg(reg, []byte{value})
}

// magRead reads n magnetometer registers starting at reg. The magnetometer
// address pointer auto-increments without a sub-address flag.
func (c *LSM303Minimal) magRead(reg uint32, n int) ([]byte, error) {
	c.bus.Lock()
	defer c.bus.Unlock()
	return c.mag.ReadReg(reg, n)
}

// magUpdate performs a read-modify-write of the bits in mask.
func (c *LSM303Minimal) magUpdate(reg uint32, mask, value uint8) error {
	b, err := c.magRead(reg, 1)
	if err != nil {
		return err
	}
	return c.magWrite(reg, (b[0]&^mask)|(value&mask))
}

// accelShiftAndScale returns the right-shift applied to the left-justified
// 16-bit sample and the scale in g per count for the current mode and range.
func (c *LSM303Minimal) accelShiftAndScale() (uint, float32) {
	hr := lsm303AccelHRMgPerLSB[c.fsCode]
	switch c.mode {
	case 0: // low-power, 8-bit
		return 8, hr * 16 / 1000
	case 1: // normal, 10-bit
		return 6, hr * 4 / 1000
	default: // high-resolution, 12-bit
		return 4, hr / 1000
	}
}

// readAccelCounts reads the six output bytes in one burst and returns the
// signed counts per axis, already shifted to the current resolution.
func (c *LSM303Minimal) readAccelCounts() ([3]int16, error) {
	b, err := c.accelReadBurst(lsm303RegOutXLA, 6)
	if err != nil {
		return [3]int16{}, err
	}
	shift, _ := c.accelShiftAndScale()
	var out [3]int16
	for i := 0; i < 3; i++ {
		raw := int16(uint16(b[2*i]) | uint16(b[2*i+1])<<8)
		out[i] = raw >> shift
	}
	return out, nil
}

// readMagRaw reads the six output bytes at 0x03 and reorders them from the
// wire order X, Z, Y (high byte first) into X, Y, Z signed counts.
func (c *LSM303Minimal) readMagRaw() ([3]int16, error) {
	b, err := c.magRead(lsm303RegOutXHM, 6)
	if err != nil {
		return [3]int16{}, err
	}
	x := int16(uint16(b[0])<<8 | uint16(b[1]))
	z := int16(uint16(b[2])<<8 | uint16(b[3]))
	y := int16(uint16(b[4])<<8 | uint16(b[5]))
	return [3]int16{x, y, z}, nil
}

// ReadAcceleration reads X, Y, Z acceleration in g.
//
// All six output bytes are read in one burst so the three axes come from the
// same sample.
func (c *LSM303Minimal) ReadAcceleration() (float32, float32, float32, error) {
	counts, err := c.readAccelCounts()
	if err != nil {
		return 0, 0, 0, err
	}
	_, scale := c.accelShiftAndScale()
	return float32(counts[0]) * scale, float32(counts[1]) * scale, float32(counts[2]) * scale, nil
}

// ReadMagnetic reads X, Y, Z magnetic flux density in µT.
//
// The bus returns X, Z, Y (high byte first); the result is reordered to X, Y, Z
// and scaled with the current gain (X/Y and Z use different gains).
func (c *LSM303Minimal) ReadMagnetic() (float32, float32, float32, error) {
	counts, err := c.readMagRaw()
	if err != nil {
		return 0, 0, 0, err
	}
	g := c.magGain
	return float32(counts[0]) / g.xyGain * 100,
		float32(counts[1]) / g.xyGain * 100,
		float32(counts[2]) / g.zGain * 100,
		nil
}

// LSM303Full extends LSM303Minimal with range, data-rate, mode and axis
// control; the high-pass filter, FIFO, inertial generators, click detection,
// magnetometer configuration, temperature, heading, and interrupts.
//
// LSM303Full embeds LSM303Minimal, so every Minimal method is promoted.
type LSM303Full struct {
	*LSM303Minimal

	intMu     sync.Mutex
	enabled   uint16                      // enabled interrupt source mask
	activeLow bool                        // INT polarity (H_LACTIVE)
	callback  func(status uint16)         // current OnInterrupt callback
	intUnsub  func()                      // unsubscribe closure from the INT pin
	pollPin   *connection.PollingInputPin // fallback poll pin when no INT pin is wired
}

// NewLSM303Full creates an LSM303Full with the same defaults as NewLSM303Minimal.
func NewLSM303Full(accelConn, magConn connection.RegisterConnection) (*LSM303Full, error) {
	m, err := NewLSM303Minimal(accelConn, magConn)
	if err != nil {
		return nil, err
	}
	return &LSM303Full{LSM303Minimal: m}, nil
}

// lsm303ODRLegal reports whether the accelerometer data rate is allowed in
// the given mode. 1620 Hz and 5376 Hz are low-power only; 1344 Hz is normal and
// high-resolution only.
func lsm303ODRLegal(rate, mode int) bool {
	switch rate {
	case 1620, 5376:
		return mode == 0
	case 1344:
		return mode != 0
	}
	return true
}

// lsm303OdrCode maps a data rate in Hz to the ODR<3:0> code, checking the mode.
func lsm303OdrCode(rate, mode int) (uint8, error) {
	if !lsm303ODRLegal(rate, mode) {
		return 0, fmt.Errorf("LSM303: %d Hz not allowed in mode %d", rate, mode)
	}
	switch rate {
	case 0:
		return 0x0, nil
	case 1:
		return 0x1, nil
	case 10:
		return 0x2, nil
	case 25:
		return 0x3, nil
	case 50:
		return 0x4, nil
	case 100:
		return 0x5, nil
	case 200:
		return 0x6, nil
	case 400:
		return 0x7, nil
	case 1620:
		return 0x8, nil
	case 1344, 5376:
		return 0x9, nil
	}
	return 0, fmt.Errorf("LSM303: unsupported data rate %d Hz", rate)
}

// lsm303Clamp limits v to the range [lo, hi].
func lsm303Clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

// lsm303Round rounds a float to the nearest integer, half away from zero.
func lsm303Round(v float64) int {
	return int(math.Round(v))
}

// lsm303ClickAxisBits maps an X=1, Y=2, Z=4 axis mask to the CLICK_CFG_A bit
// positions (XS = bit 0, YS = bit 2, ZS = bit 4).
func lsm303ClickAxisBits(mask uint8) (uint8, error) {
	if mask > 0x07 {
		return 0, fmt.Errorf("LSM303: click axis mask 0x%02X out of range (X=1, Y=2, Z=4)", mask)
	}
	var bits uint8
	if mask&0x01 != 0 {
		bits |= 0x01
	}
	if mask&0x02 != 0 {
		bits |= 0x04
	}
	if mask&0x04 != 0 {
		bits |= 0x10
	}
	return bits, nil
}

// SetRange sets the full-scale range to ±2, ±4, ±8 or ±16 g.
//
// The sensitivity is updated for the new range. Returns an error for any
// other value.
func (c *LSM303Full) SetRange(rangeG int) error {
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
		return fmt.Errorf("LSM303: range must be 2, 4, 8 or 16 g, got %d", rangeG)
	}
	if err := c.accelUpdate(lsm303RegCtrl4A, 0x30, code<<4); err != nil {
		return err
	}
	c.fsCode = code
	return nil
}

// SetDataRate sets the accelerometer output data rate in Hz.
//
// Accepts 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376.
// 1620 and 5376 Hz are allowed only in low-power mode; 1344 Hz only in normal
// or high-resolution mode. Returns an error for any other combination.
func (c *LSM303Full) SetDataRate(rateHz int) error {
	code, err := lsm303OdrCode(rateHz, c.mode)
	if err != nil {
		return err
	}
	if err := c.accelUpdate(lsm303RegCtrl1A, 0xF0, code<<4); err != nil {
		return err
	}
	c.odrHz = rateHz
	return nil
}

// SetMode selects the accelerometer power/resolution mode.
//
// mode is 0 for low-power (LPen=1, HR=0; 8-bit), 1 for normal (LPen=0, HR=0;
// 10-bit), or 2 for high-resolution (LPen=0, HR=1; 12-bit). Returns an error
// if the current data rate is not allowed in the new mode.
func (c *LSM303Full) SetMode(mode int) error {
	if mode < 0 || mode > 2 {
		return fmt.Errorf("LSM303: mode must be 0, 1 or 2, got %d", mode)
	}
	if !lsm303ODRLegal(c.odrHz, mode) {
		return fmt.Errorf("LSM303: current data rate %d Hz not allowed in mode %d", c.odrHz, mode)
	}
	var lpen, hr uint8
	switch mode {
	case 0:
		lpen = 1
	case 2:
		hr = 1
	}
	if err := c.accelUpdate(lsm303RegCtrl1A, 0x08, lpen<<3); err != nil {
		return err
	}
	if err := c.accelUpdate(lsm303RegCtrl4A, 0x08, hr<<3); err != nil {
		return err
	}
	c.mode = mode
	return nil
}

// SetAxes enables or disables the X, Y and Z acceleration axes.
func (c *LSM303Full) SetAxes(x, y, z bool) error {
	var v uint8
	if x {
		v |= 0x01
	}
	if y {
		v |= 0x02
	}
	if z {
		v |= 0x04
	}
	return c.accelUpdate(lsm303RegCtrl1A, 0x07, v)
}

// PowerDown puts the accelerometer in power-down by setting ODR to 0. The
// register contents are kept.
func (c *LSM303Full) PowerDown() error {
	if err := c.accelUpdate(lsm303RegCtrl1A, 0xF0, 0); err != nil {
		return err
	}
	c.odrHz = 0
	return nil
}

// ReadAccelerationRaw reads signed counts per axis at the current resolution
// (the 16-bit sample shifted right by 4, 6 or 8 bits).
func (c *LSM303Full) ReadAccelerationRaw() (int16, int16, int16, error) {
	counts, err := c.readAccelCounts()
	if err != nil {
		return 0, 0, 0, err
	}
	return counts[0], counts[1], counts[2], nil
}

// NewDataAvailable reports whether a new XYZ sample is available (STATUS_REG_A.ZYXDA).
func (c *LSM303Full) NewDataAvailable() (bool, error) {
	s, err := c.accelRead(lsm303RegStatusA)
	if err != nil {
		return false, err
	}
	return s&0x08 != 0, nil
}

// ReadStatus returns the raw STATUS_REG_A byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA).
func (c *LSM303Full) ReadStatus() (uint8, error) {
	return c.accelRead(lsm303RegStatusA)
}

// SetHighPass writes CTRL_REG2_A.
//
// mode is HPM<1:0> (0 normal, 1 reference filter, 2 normal, 3 autoreset on
// interrupt). cutoff is HPCF<2:1> (0–3); its Hz values are not tabulated in
// this datasheet revision. filterOutput routes filtered data to the output
// registers and FIFO; onIA1, onIA2 and onClick enable the filter for each
// function.
func (c *LSM303Full) SetHighPass(mode, cutoff int, filterOutput, onIA1, onIA2, onClick bool) error {
	if mode < 0 || mode > 3 {
		return fmt.Errorf("LSM303: high-pass mode must be 0-3, got %d", mode)
	}
	if cutoff < 0 || cutoff > 3 {
		return fmt.Errorf("LSM303: high-pass cutoff must be 0-3, got %d", cutoff)
	}
	v := uint8(mode)<<6 | uint8(cutoff)<<4
	if filterOutput {
		v |= 0x08
	}
	if onClick {
		v |= 0x04
	}
	if onIA2 {
		v |= 0x02
	}
	if onIA1 {
		v |= 0x01
	}
	return c.accelWrite(lsm303RegCtrl2A, v)
}

// ResetHighPass reads REFERENCE_A, which resets the filter in HPM = 0, and returns the value.
func (c *LSM303Full) ResetHighPass() (uint8, error) {
	return c.accelRead(lsm303RegReferenceA)
}

// SetReference writes the REFERENCE_A value used when HPM = 1 (0–255).
func (c *LSM303Full) SetReference(value int) error {
	if value < 0 || value > 255 {
		return fmt.Errorf("LSM303: reference must be 0-255, got %d", value)
	}
	return c.accelWrite(lsm303RegReferenceA, uint8(value))
}

// SetFifo configures the FIFO.
//
// mode is 0 bypass, 1 FIFO, 2 stream or 3 trigger. watermark is FTH<4:0>
// (0–31). triggerInt2 selects INT2 as the trigger source in trigger mode.
// FIFO_EN is set in CTRL_REG5_A for modes 1–3. Returning to bypass clears the buffer.
func (c *LSM303Full) SetFifo(mode, watermark int, triggerInt2 bool) error {
	if mode < 0 || mode > 3 {
		return fmt.Errorf("LSM303: FIFO mode must be 0-3, got %d", mode)
	}
	if watermark < 0 || watermark > 31 {
		return fmt.Errorf("LSM303: FIFO watermark must be 0-31, got %d", watermark)
	}
	v := uint8(mode)<<6 | uint8(watermark)
	if triggerInt2 {
		v |= 0x20
	}
	if mode == 0 {
		// Bypass: write FIFO_CTRL first so the buffer is cleared, then disable FIFO_EN.
		if err := c.accelWrite(lsm303RegFifoCtrlA, v); err != nil {
			return err
		}
		return c.accelUpdate(lsm303RegCtrl5A, 0x40, 0)
	}
	if err := c.accelUpdate(lsm303RegCtrl5A, 0x40, 0x40); err != nil {
		return err
	}
	return c.accelWrite(lsm303RegFifoCtrlA, v)
}

// FifoLevel returns FSS<4:0>, the number of unread sample sets (0–32).
func (c *LSM303Full) FifoLevel() (uint8, error) {
	s, err := c.accelRead(lsm303RegFifoSrcA)
	if err != nil {
		return 0, err
	}
	return s & 0x1F, nil
}

// ReadFifo drains the FIFO and returns sample sets as (x, y, z) in g.
//
// count is the maximum number of sample sets to read; count <= 0 reads all
// unread sets (FSS). Each sample is one 6-byte burst, which pops one set.
func (c *LSM303Full) ReadFifo(count int) ([][3]float32, error) {
	n, err := c.FifoLevel()
	if err != nil {
		return nil, err
	}
	total := int(n)
	if count > 0 && count < total {
		total = count
	}
	out := make([][3]float32, 0, total)
	for i := 0; i < total; i++ {
		x, y, z, err := c.ReadAcceleration()
		if err != nil {
			return nil, err
		}
		out = append(out, [3]float32{x, y, z})
	}
	return out, nil
}

// ReadFifoStatus decodes FIFO_SRC_REG_A into (watermark, overrun, empty, level).
func (c *LSM303Full) ReadFifoStatus() (bool, bool, bool, uint8, error) {
	s, err := c.accelRead(lsm303RegFifoSrcA)
	if err != nil {
		return false, false, false, 0, err
	}
	return s&0x80 != 0, s&0x40 != 0, s&0x20 != 0, s & 0x1F, nil
}

// lsm303GenRegs returns the INTx_CFG_A, INTx_THS_A, and INTx_DURATION_A registers for generator 1 or 2.
func lsm303GenRegs(generator int) (uint32, uint32, uint32, error) {
	switch generator {
	case 1:
		return lsm303RegInt1CfgA, lsm303RegInt1ThsA, lsm303RegInt1DurA, nil
	case 2:
		return lsm303RegInt2CfgA, lsm303RegInt2ThsA, lsm303RegInt2DurA, nil
	}
	return 0, 0, 0, fmt.Errorf("LSM303: generator must be 1 or 2, got %d", generator)
}

// SetMotionInterrupt configures inertial interrupt generator 1 or 2.
//
// events is the 6-bit ZHIE/ZLIE/YHIE/YLIE/XHIE/XLIE mask (bit 5 = ZHIE, bit 0 = XLIE).
// andMode selects AND combination of the enabled events instead of OR.
// thresholdG and durationMs are converted using the current range and data rate.
// The generator is not routed to a pin until EnableInterrupt is called.
func (c *LSM303Full) SetMotionInterrupt(generator int, events uint8, thresholdG, durationMs float32, andMode bool) error {
	cfgReg, thsReg, durReg, err := lsm303GenRegs(generator)
	if err != nil {
		return err
	}
	if events > 0x3F {
		return fmt.Errorf("LSM303: events mask must be 6 bits, got 0x%02X", events)
	}
	cfg := events
	if andMode {
		cfg |= 0x80
	}
	fsG := float64(lsm303AccelFullScaleG[c.fsCode])
	ths := lsm303Clamp(lsm303Round(float64(thresholdG)*128/fsG), 0, 127)
	dur := lsm303Clamp(lsm303Round(float64(durationMs)*float64(c.odrHz)/1000), 0, 127)
	if err := c.accelWrite(cfgReg, cfg); err != nil {
		return err
	}
	if err := c.accelWrite(thsReg, uint8(ths)); err != nil {
		return err
	}
	return c.accelWrite(durReg, uint8(dur))
}

// SetFreeFall configures generator 1 or 2 for free-fall: all three axes low, AND mode (INTx_CFG_A = 0x95).
func (c *LSM303Full) SetFreeFall(generator int, thresholdG, durationMs float32) error {
	return c.SetMotionInterrupt(generator, 0x15, thresholdG, durationMs, true)
}

// SetOrientation configures 6D orientation recognition on generator 1 or 2.
//
// position = false selects 6D movement recognition (OR, one ODR period pulse);
// position = true selects 6D position recognition (AND, level while in a zone).
// fourD enables 4D detection (D4D_INTx in CTRL_REG5_A).
func (c *LSM303Full) SetOrientation(generator int, position, fourD bool) error {
	cfgReg, _, _, err := lsm303GenRegs(generator)
	if err != nil {
		return err
	}
	cfg := uint8(0x40 | 0x3F) // 6D plus all six direction zones
	if position {
		cfg |= 0x80
	}
	if err := c.accelWrite(cfgReg, cfg); err != nil {
		return err
	}
	d4dMask := uint8(0x01)
	if generator == 1 {
		d4dMask = 0x04
	}
	var d4dValue uint8
	if fourD {
		d4dValue = d4dMask
	}
	return c.accelUpdate(lsm303RegCtrl5A, d4dMask, d4dValue)
}

// SetLatch latches generator 1 or 2 until INTx_SOURCE_A is read (LIR_INT1 / LIR_INT2).
func (c *LSM303Full) SetLatch(generator int, enabled bool) error {
	var mask uint8
	switch generator {
	case 1:
		mask = 0x08
	case 2:
		mask = 0x02
	default:
		return fmt.Errorf("LSM303: generator must be 1 or 2, got %d", generator)
	}
	var v uint8
	if enabled {
		v = mask
	}
	return c.accelUpdate(lsm303RegCtrl5A, mask, v)
}

// ReadInterruptSource reads INTx_SOURCE_A for generator 1 or 2. Reading clears the IA flag and the INT pin.
func (c *LSM303Full) ReadInterruptSource(generator int) (uint8, error) {
	switch generator {
	case 1:
		return c.accelRead(lsm303RegInt1SrcA)
	case 2:
		return c.accelRead(lsm303RegInt2SrcA)
	}
	return 0, fmt.Errorf("LSM303: generator must be 1 or 2, got %d", generator)
}

// SetClick configures single and double click detection.
//
// single and double are axis masks (X = 1, Y = 2, Z = 4). thresholdG is the
// click threshold in g. timeLimitMs, latencyMs and windowMs are converted to
// ODR periods. The click is routed to a pin by EnableInterrupt(LSM303SourceClick).
func (c *LSM303Full) SetClick(single, double uint8, thresholdG, timeLimitMs, latencyMs, windowMs float32) error {
	s, err := lsm303ClickAxisBits(single)
	if err != nil {
		return err
	}
	d, err := lsm303ClickAxisBits(double)
	if err != nil {
		return err
	}
	fsG := float64(lsm303AccelFullScaleG[c.fsCode])
	ths := lsm303Clamp(lsm303Round(float64(thresholdG)*128/fsG), 0, 127)
	odr := float64(c.odrHz) / 1000
	limit := lsm303Clamp(lsm303Round(float64(timeLimitMs)*odr), 0, 127)
	latency := lsm303Clamp(lsm303Round(float64(latencyMs)*odr), 0, 255)
	window := lsm303Clamp(lsm303Round(float64(windowMs)*odr), 0, 255)

	if err := c.accelWrite(lsm303RegClickCfgA, s|d<<1); err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegClickThsA, uint8(ths)); err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegTimeLimitA, uint8(limit)); err != nil {
		return err
	}
	if err := c.accelWrite(lsm303RegTimeLatencyA, uint8(latency)); err != nil {
		return err
	}
	return c.accelWrite(lsm303RegTimeWindowA, uint8(window))
}

// ReadClickSource reads CLICK_SRC_A. Reading clears the click interrupt.
func (c *LSM303Full) ReadClickSource() (uint8, error) {
	return c.accelRead(lsm303RegClickSrcA)
}

// SetInterruptPolarity sets INT1 and INT2 polarity (H_LACTIVE). activeLow = false is active high (the default).
//
// The chosen polarity sets the edge that OnInterrupt listens for.
func (c *LSM303Full) SetInterruptPolarity(activeLow bool) error {
	var v uint8
	if activeLow {
		v = 0x02
	}
	if err := c.accelUpdate(lsm303RegCtrl6A, 0x02, v); err != nil {
		return err
	}
	c.intMu.Lock()
	c.activeLow = activeLow
	c.intMu.Unlock()
	return nil
}

// SetMagRange sets the magnetometer full scale to 130, 190, 250, 400, 470, 560 or 810 µT.
func (c *LSM303Full) SetMagRange(rangeUT int) error {
	for _, g := range lsm303MagGains {
		if g.rangeUT == rangeUT {
			if err := c.magWrite(lsm303RegCrbM, g.code<<5); err != nil {
				return err
			}
			c.magGain = g
			return nil
		}
	}
	return fmt.Errorf("LSM303: magnetometer range must be 130, 190, 250, 400, 470, 560 or 810 µT, got %d", rangeUT)
}

// SetMagDataRate sets the magnetometer output rate in Hz. Accepts 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220. TEMP_EN is preserved.
func (c *LSM303Full) SetMagDataRate(rateHz float32) error {
	var code uint8
	switch rateHz {
	case 0.75:
		code = 0
	case 1.5:
		code = 1
	case 3:
		code = 2
	case 7.5:
		code = 3
	case 15:
		code = 4
	case 30:
		code = 5
	case 75:
		code = 6
	case 220:
		code = 7
	default:
		return fmt.Errorf("LSM303: magnetometer rate %g Hz not supported", rateHz)
	}
	return c.magUpdate(lsm303RegCraM, 0x1C, code<<2)
}

// SetMagMode sets the magnetometer mode: 0 continuous, 1 single conversion (then sleep), 2 sleep.
func (c *LSM303Full) SetMagMode(mode int) error {
	if mode < 0 || mode > 2 {
		return fmt.Errorf("LSM303: magnetometer mode must be 0, 1 or 2, got %d", mode)
	}
	return c.magWrite(lsm303RegMrM, uint8(mode))
}

// ReadMagneticRaw reads signed magnetometer counts in X, Y, Z order.
func (c *LSM303Full) ReadMagneticRaw() (int16, int16, int16, error) {
	counts, err := c.readMagRaw()
	if err != nil {
		return 0, 0, 0, err
	}
	return counts[0], counts[1], counts[2], nil
}

// MagDataReady reports whether a new magnetometer sample is ready (SR_REG_M.DRDY).
func (c *LSM303Full) MagDataReady() (bool, error) {
	b, err := c.magRead(lsm303RegSrM, 1)
	if err != nil {
		return false, err
	}
	return b[0]&0x01 != 0, nil
}

// ReadMagStatus decodes SR_REG_M into (drdy, lock).
func (c *LSM303Full) ReadMagStatus() (bool, bool, error) {
	b, err := c.magRead(lsm303RegSrM, 1)
	if err != nil {
		return false, false, err
	}
	return b[0]&0x01 != 0, b[0]&0x02 != 0, nil
}

// ReadMagIdentity reads IRA, IRB, IRC (0x48 0x34 0x33 on a genuine part).
func (c *LSM303Full) ReadMagIdentity() ([]byte, error) {
	return c.magRead(lsm303RegIraM, 3)
}

// EnableTemperature sets or clears TEMP_EN in CRA_REG_M.
func (c *LSM303Full) EnableTemperature(enabled bool) error {
	var v uint8
	if enabled {
		v = 0x80
	}
	if err := c.magUpdate(lsm303RegCraM, 0x80, v); err != nil {
		return err
	}
	c.tempEnabled = enabled
	return nil
}

// ReadTemperatureDelta reads the relative temperature in °C (8 LSB/°C).
//
// The datasheet gives no absolute reference, so the value is a delta only.
// TEMP_EN is set on first use. The two bytes are read as separate single-register
// accesses because the magnetometer pointer wraps after 0x0C.
func (c *LSM303Full) ReadTemperatureDelta() (float32, error) {
	if !c.tempEnabled {
		if err := c.EnableTemperature(true); err != nil {
			return 0, err
		}
	}
	h, err := c.magRead(lsm303RegTempOutHM, 1)
	if err != nil {
		return 0, err
	}
	l, err := c.magRead(lsm303RegTempOutLM, 1)
	if err != nil {
		return 0, err
	}
	raw := int16(uint16(h[0])<<8|uint16(l[0])) >> 4
	return float32(raw) / 8, nil
}

// Heading returns the compass heading in degrees (0–360), using atan2(y, x).
//
// tiltCompensate = true reads both sensors and corrects for pitch and roll
// using the accelerometer; false uses the magnetometer only. Both sensors are
// assumed to share a body frame (the datasheet gives no alignment). Returns an
// error when tilt compensation is requested and the acceleration vector is zero.
func (c *LSM303Full) Heading(tiltCompensate bool) (float32, error) {
	mx, my, mz, err := c.ReadMagnetic()
	if err != nil {
		return 0, err
	}
	var xh, yh float64
	if !tiltCompensate {
		xh = float64(mx)
		yh = float64(my)
	} else {
		ax, ay, az, err := c.ReadAcceleration()
		if err != nil {
			return 0, err
		}
		norm := math.Sqrt(float64(ax)*float64(ax) + float64(ay)*float64(ay) + float64(az)*float64(az))
		if norm == 0 {
			return 0, fmt.Errorf("LSM303: zero acceleration vector, cannot tilt-compensate")
		}
		nx, ny, nz := float64(ax)/norm, float64(ay)/norm, float64(az)/norm
		roll := math.Atan2(ny, nz)
		pitch := math.Atan2(-nx, ny*math.Sin(roll)+nz*math.Cos(roll))
		mxf, myf, mzf := float64(mx), float64(my), float64(mz)
		xh = mxf*math.Cos(pitch) + mzf*math.Sin(pitch)
		yh = mxf*math.Sin(roll)*math.Sin(pitch) + myf*math.Cos(roll) - mzf*math.Sin(roll)*math.Cos(pitch)
	}
	deg := math.Atan2(yh, xh) * 180 / math.Pi
	if deg < 0 {
		deg += 360
	}
	return float32(deg), nil
}

// lsm303IntRoute describes where one interrupt source's enable bit lives.
type lsm303IntRoute struct {
	reg  uint32
	mask uint8
}

// lsm303RouteFor returns the register and bit that route source to the given pin.
// pin is ignored for INT1-only and INT2-only sources.
func lsm303RouteFor(source uint16, pin int) (lsm303IntRoute, error) {
	switch source {
	case LSM303SourceDataReady:
		return lsm303IntRoute{lsm303RegCtrl3A, 0x10}, nil
	case LSM303SourceWatermark:
		return lsm303IntRoute{lsm303RegCtrl3A, 0x04}, nil
	case LSM303SourceOverrun:
		return lsm303IntRoute{lsm303RegCtrl3A, 0x02}, nil
	case LSM303SourceIA1:
		if pin == 2 {
			return lsm303IntRoute{lsm303RegCtrl6A, 0x40}, nil
		}
		return lsm303IntRoute{lsm303RegCtrl3A, 0x40}, nil
	case LSM303SourceIA2:
		if pin == 2 {
			return lsm303IntRoute{lsm303RegCtrl6A, 0x20}, nil
		}
		return lsm303IntRoute{lsm303RegCtrl3A, 0x20}, nil
	case LSM303SourceClick:
		if pin == 2 {
			return lsm303IntRoute{lsm303RegCtrl6A, 0x80}, nil
		}
		return lsm303IntRoute{lsm303RegCtrl3A, 0x80}, nil
	case LSM303SourceBoot:
		return lsm303IntRoute{lsm303RegCtrl6A, 0x10}, nil
	case LSM303SourceMagDataReady:
		return lsm303IntRoute{}, fmt.Errorf("LSM303: magnetometer DRDY is on its own pin and cannot be routed")
	}
	return lsm303IntRoute{}, fmt.Errorf("LSM303: unknown interrupt source 0x%04X", source)
}

// EnableInterrupt routes one source to INT1 (pin = 1) or INT2 (pin = 2).
//
// pin is ignored for INT1-only sources (DataReady, Watermark, Overrun) and the
// INT2-only Boot source. LSM303SourceMagDataReady returns an error. This only
// routes the source: the generator or function behind it is configured with
// SetMotionInterrupt, SetClick, SetFifo, and so on.
func (c *LSM303Full) EnableInterrupt(source uint16, pin int) error {
	return c.routeInterrupt(source, pin, true)
}

// DisableInterrupt removes one source from INT1 (pin = 1) or INT2 (pin = 2).
func (c *LSM303Full) DisableInterrupt(source uint16, pin int) error {
	return c.routeInterrupt(source, pin, false)
}

func (c *LSM303Full) routeInterrupt(source uint16, pin int, on bool) error {
	if pin != 1 && pin != 2 {
		return fmt.Errorf("LSM303: pin must be 1 or 2, got %d", pin)
	}
	route, err := lsm303RouteFor(source, pin)
	if err != nil {
		return err
	}
	var v uint8
	if on {
		v = route.mask
	}
	if err := c.accelUpdate(route.reg, route.mask, v); err != nil {
		return err
	}
	c.intMu.Lock()
	if on {
		c.enabled |= source
	} else {
		c.enabled &^= source
	}
	c.intMu.Unlock()
	return nil
}

// PollInterrupt reads the status of every enabled source and returns the combined status word.
//
// Bit layout: 0 DataReady, 1 Watermark, 2 Overrun, 3 IA1, 4 IA2, 5 Click, 7 MagDataReady.
// Reading INTx_SOURCE_A and CLICK_SRC_A clears their latched flags. The boot
// source has no flag and is never reported.
func (c *LSM303Full) PollInterrupt() (uint16, error) {
	c.intMu.Lock()
	enabled := c.enabled
	c.intMu.Unlock()

	var status uint16
	if enabled&LSM303SourceDataReady != 0 {
		s, err := c.accelRead(lsm303RegStatusA)
		if err != nil {
			return 0, err
		}
		if s&0x08 != 0 {
			status |= LSM303SourceDataReady
		}
	}
	if enabled&(LSM303SourceWatermark|LSM303SourceOverrun) != 0 {
		s, err := c.accelRead(lsm303RegFifoSrcA)
		if err != nil {
			return 0, err
		}
		if enabled&LSM303SourceWatermark != 0 && s&0x80 != 0 {
			status |= LSM303SourceWatermark
		}
		if enabled&LSM303SourceOverrun != 0 && s&0x40 != 0 {
			status |= LSM303SourceOverrun
		}
	}
	if enabled&LSM303SourceIA1 != 0 {
		s, err := c.accelRead(lsm303RegInt1SrcA)
		if err != nil {
			return 0, err
		}
		if s&0x40 != 0 {
			status |= LSM303SourceIA1
		}
	}
	if enabled&LSM303SourceIA2 != 0 {
		s, err := c.accelRead(lsm303RegInt2SrcA)
		if err != nil {
			return 0, err
		}
		if s&0x40 != 0 {
			status |= LSM303SourceIA2
		}
	}
	if enabled&LSM303SourceClick != 0 {
		s, err := c.accelRead(lsm303RegClickSrcA)
		if err != nil {
			return 0, err
		}
		if s&0x40 != 0 {
			status |= LSM303SourceClick
		}
	}
	if enabled&LSM303SourceMagDataReady != 0 {
		b, err := c.magRead(lsm303RegSrM, 1)
		if err != nil {
			return 0, err
		}
		if b[0]&0x01 != 0 {
			status |= LSM303SourceMagDataReady
		}
	}
	return status, nil
}

// OnInterrupt subscribes callback to INT1 (pin = 1).
//
// The callback receives the non-zero PollInterrupt status word. The edge is
// rising for the default active-high polarity and falling after
// SetInterruptPolarity(true). If the connection has no INT pin wired, a 5 ms
// polling goroutine calls PollInterrupt instead. INT2 has no InputPin in
// this driver's constructor (only the accelerometer connection is passed in),
// so pin = 2 returns an error. Route INT2 sources with EnableInterrupt(src, 2)
// and read them with PollInterrupt.
func (c *LSM303Full) OnInterrupt(pin int, callback func(status uint16)) error {
	if pin != 1 {
		return fmt.Errorf("LSM303: OnInterrupt supports pin 1 only (the accelerometer connection carries INT1)")
	}
	c.intMu.Lock()
	oldUnsub, oldPoll := c.intUnsub, c.pollPin
	c.intUnsub, c.pollPin = nil, nil
	c.callback = callback
	activeLow := c.activeLow
	c.intMu.Unlock()

	if oldUnsub != nil {
		oldUnsub()
	}
	if oldPoll != nil {
		_ = oldPoll.Close()
	}

	pinIn := c.accel.IntPin()
	var poll *connection.PollingInputPin
	if pinIn == nil {
		poll = connection.NewDefaultPollingInputPin()
		pinIn = poll
	}
	trigger := connection.Rising
	if activeLow {
		trigger = connection.Falling
	}
	unsub := pinIn.OnEdge(trigger, func() { c.handleEdge() })

	c.intMu.Lock()
	c.intUnsub = unsub
	c.pollPin = poll
	c.intMu.Unlock()
	return nil
}

// OffInterrupt unsubscribes the INT1 handler and stops the polling fallback, if any.
func (c *LSM303Full) OffInterrupt(pin int) error {
	if pin != 1 {
		return fmt.Errorf("LSM303: OffInterrupt supports pin 1 only")
	}
	c.intMu.Lock()
	unsub, poll := c.intUnsub, c.pollPin
	c.intUnsub, c.pollPin, c.callback = nil, nil, nil
	c.intMu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

// handleEdge runs on the INT edge (or polling tick). It reads the status and dispatches a non-zero result to the callback.
func (c *LSM303Full) handleEdge() {
	status, err := c.PollInterrupt()
	if err != nil || status == 0 {
		return
	}
	c.intMu.Lock()
	cb := c.callback
	c.intMu.Unlock()
	if cb != nil {
		cb(status)
	}
}

// Reboot triggers the accelerometer memory reboot (CTRL_REG5_A.BOOT) and waits 5 ms.
//
// The datasheet does not say which control registers survive the reboot. Call
// the constructor again or reapply configuration afterwards.
func (c *LSM303Full) Reboot() error {
	if err := c.accelUpdate(lsm303RegCtrl5A, 0x80, 0x80); err != nil {
		return err
	}
	time.Sleep(5 * time.Millisecond)
	return nil
}

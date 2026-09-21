package imu

import (
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MPU-9255 register map (subset; shared with MPU-9250 plus wake-on-motion
// helpers and a different WHO_AM_I value).
const (
	reg9255SmplrtDiv    = 0x19
	reg9255Config       = 0x1A
	reg9255GyroConfig   = 0x1B
	reg9255AccelConfig  = 0x1C
	reg9255AccelConfig2 = 0x1D
	reg9255LpAccelODR   = 0x1E
	reg9255WomThr       = 0x1F
	reg9255FifoEn       = 0x23
	reg9255IntPinCfg    = 0x37
	reg9255IntEnable    = 0x38
	reg9255IntStatus    = 0x3A
	reg9255AccelXoutH   = 0x3B
	reg9255TempOutH     = 0x41
	reg9255GyroXoutH    = 0x43
	reg9255MotDetectCtrl = 0x69
	reg9255UserCtrl     = 0x6A
	reg9255PwrMgmt1     = 0x6B
	reg9255PwrMgmt2     = 0x6C
	reg9255FifoCountH   = 0x72
	reg9255FifoR_W      = 0x74
	reg9255WhoAmI       = 0x75

	whoAmI9255Value = 0x73
)

// AK8963 magnetometer registers (address 0x0C) — identical hardware to
// MPU-9250's embedded AK8963, so reuse the constants already declared in
// mpu9250.go (same package) rather than redeclaring them here.

const (
	mpu9255ResetDelay        = 100 * time.Millisecond
	mpu9255GyroStartupDelay  = 35 * time.Millisecond
	mpu9255MagPowerDownDelay = 10 * time.Millisecond
)

// MPU9255Minimal is the MPU-9255 9-axis MotionTracking device driver
// (3-axis accelerometer + 3-axis gyroscope) — minimal interface. No
// magnetometer or wake-on-motion in Minimal; see MPU9255Full.
//
// Performs device reset, WHO_AM_I verification (always 0x73 for MPU-9255;
// MPU-9250 reads 0x71 at the same address), and configures sensible
// defaults at construction.
type MPU9255Minimal struct {
	connection connection.Connection
	accelFs    uint8
	gyroFs     uint8
}

// NewMPU9255Minimal creates a new MPU9255Minimal, resets the device,
// verifies WHO_AM_I, and configures defaults at construction.
//
// connection must be a configured I²C connection bound to the device's
// 7-bit address (0x68 default, 0x69 alternate).
func NewMPU9255Minimal(t connection.Connection) (*MPU9255Minimal, error) {
	d := &MPU9255Minimal{connection: t}
	if err := d.writeReg(reg9255PwrMgmt1, 0x80); err != nil {
		return nil, err
	}
	time.Sleep(mpu9255ResetDelay)
	if err := d.writeReg(reg9255PwrMgmt1, 0x01); err != nil {
		return nil, err
	}
	who, err := d.readReg(reg9255WhoAmI)
	if err != nil {
		return nil, err
	}
	if who != whoAmI9255Value {
		return nil, fmt.Errorf("MPU9255 WHO_AM_I: expected 0x%02X, got 0x%02X", whoAmI9255Value, who)
	}
	if err := d.writeReg(reg9255GyroConfig, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(reg9255AccelConfig, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(reg9255AccelConfig2, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(reg9255Config, 0x03); err != nil {
		return nil, err
	}
	if err := d.writeReg(reg9255SmplrtDiv, 0x04); err != nil {
		return nil, err
	}
	time.Sleep(mpu9255GyroStartupDelay)
	return d, nil
}

func (d *MPU9255Minimal) writeReg(reg, value byte) error {
	return d.connection.Write([]byte{reg, value})
}

func (d *MPU9255Minimal) readReg(reg byte) (byte, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *MPU9255Minimal) readReg16Signed(reg byte) (int16, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return int16(uint16(b[0])<<8 | uint16(b[1])), nil
}

// Accel reads the 3-axis linear acceleration.
//
// Returns (x, y, z) in m/s².
func (d *MPU9255Minimal) Accel() (float32, float32, float32, error) {
	b, err := d.connection.WriteRead([]byte{reg9255AccelXoutH}, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	ax := int16(uint16(b[0])<<8 | uint16(b[1]))
	ay := int16(uint16(b[2])<<8 | uint16(b[3]))
	az := int16(uint16(b[4])<<8 | uint16(b[5]))
	sens := accelSensitivity[d.accelFs]
	return float32(ax) / sens * 9.80665,
		float32(ay) / sens * 9.80665,
		float32(az) / sens * 9.80665,
		nil
}

// Gyro reads the 3-axis angular rate.
//
// Returns (x, y, z) in rad/s.
func (d *MPU9255Minimal) Gyro() (float32, float32, float32, error) {
	b, err := d.connection.WriteRead([]byte{reg9255GyroXoutH}, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	gx := int16(uint16(b[0])<<8 | uint16(b[1]))
	gy := int16(uint16(b[2])<<8 | uint16(b[3]))
	gz := int16(uint16(b[4])<<8 | uint16(b[5]))
	sens := gyroSensitivity[d.gyroFs]
	const piOver180 = float32(3.141592653589793) / 180.0
	return float32(gx) / sens * piOver180,
		float32(gy) / sens * piOver180,
		float32(gz) / sens * piOver180,
		nil
}

// MPU9255Full is the MPU-9255 9-axis MotionTracking device driver — full
// interface. Extends MPU9255Minimal with configuration, magnetometer access
// via the on-board AK8963, wake-on-motion, FIFO, and power control.
//
// The magnetometer is accessed via the chip's I²C bypass mode (BYPASS_EN
// in INT_PIN_CFG); the host can then address the AK8963 directly at 0x0C.
// NewMPU9255Full takes a connection factory that produces a new I²C
// connection bound to a caller-supplied 7-bit address on the same bus.
type MPU9255Full struct {
	*MPU9255Minimal
	magConnection connection.Connection
	magEnabled    bool
	magScaleX     float32
	magScaleY     float32
	magScaleZ     float32
	magBits       uint8
}

// NewMPU9255Full creates a new MPU9255Full with the same initialization as
// NewMPU9255Minimal. The magFactory is used to construct the AK8963
// connection on demand; it must return a connection bound to the given
// 7-bit address (0x0C for the AK8963).
func NewMPU9255Full(t connection.Connection, magFactory ConnectionFactory) (*MPU9255Full, error) {
	m, err := NewMPU9255Minimal(t)
	if err != nil {
		return nil, err
	}
	if magFactory == nil {
		return nil, fmt.Errorf("MPU9255Full: magFactory is required for AK8963 access")
	}
	mag, err := magFactory(0x0C)
	if err != nil {
		return nil, fmt.Errorf("MPU9255Full: open AK8963 connection: %w", err)
	}
	return &MPU9255Full{
		MPU9255Minimal: m,
		magConnection:  mag,
	}, nil
}

// Close releases the AK8963 secondary connection. The primary MPU9255
// connection is owned by the caller and not closed here.
func (d *MPU9255Full) Close() error {
	if d.magConnection != nil {
		return d.magConnection.Close()
	}
	return nil
}

// EnableMag initialises the on-board AK8963 magnetometer and switches it
// into continuous measurement mode.
//
// bits: 14 (BIT=0) or 16 (BIT=1).
// mode: 1=single, 2=8 Hz continuous, 6=100 Hz continuous.
func (d *MPU9255Full) EnableMag(bits, mode uint8) error {
	// 1) Enable I2C bypass so the host can address the AK8963 directly.
	if err := d.writeReg(reg9255IntPinCfg, 0x22); err != nil {
		return err
	}
	// 2) Power down the AK8963, wait 10 ms.
	if err := d.magConnection.Write([]byte{ak8963CNTL1, ak8963ModePowerDown}); err != nil {
		return err
	}
	time.Sleep(mpu9255MagPowerDownDelay)
	// 3) Enter fuse ROM access mode, wait 10 ms.
	if err := d.magConnection.Write([]byte{ak8963CNTL1, ak8963ModeFuseROM}); err != nil {
		return err
	}
	time.Sleep(mpu9255MagPowerDownDelay)
	// 4) Read ASAX/Y/Z factory calibration.
	asax, err := d.magConnection.WriteRead([]byte{ak8963ASAX}, 3)
	if err != nil {
		return err
	}
	d.magScaleX = (float32(asax[0]) - 128.0) / 256.0 + 1.0
	d.magScaleY = (float32(asax[1]) - 128.0) / 256.0 + 1.0
	d.magScaleZ = (float32(asax[2]) - 128.0) / 256.0 + 1.0
	// 5) Power down, wait 10 ms.
	if err := d.magConnection.Write([]byte{ak8963CNTL1, ak8963ModePowerDown}); err != nil {
		return err
	}
	time.Sleep(mpu9255MagPowerDownDelay)
	// 6) Configure continuous mode at the requested resolution.
	var bitFlag uint8
	if bits == 16 {
		bitFlag = 0x10
	}
	if err := d.magConnection.Write([]byte{ak8963CNTL1, bitFlag | (mode & 0x0F)}); err != nil {
		return err
	}
	d.magEnabled = true
	d.magBits = bits
	return nil
}

// Mag reads the 3-axis magnetic field strength.
//
// Returns (x, y, z) in µT. Mag must have been enabled first via EnableMag.
func (d *MPU9255Full) Mag() (float32, float32, float32, error) {
	if !d.magEnabled {
		return 0, 0, 0, fmt.Errorf("MPU9255 Mag: magnetometer not enabled (call EnableMag first)")
	}
	// Read 7 bytes from 0x03 (HXL) through 0x09 (ST2). ST2 must be read
	// to unlock the next measurement.
	b, err := d.magConnection.WriteRead([]byte{ak8963HXL}, 7)
	if err != nil {
		return 0, 0, 0, err
	}
	if b[6]&0x08 != 0 {
		return 0, 0, 0, fmt.Errorf("MPU9255 Mag: AK8963 overflow")
	}
	mx := int16(uint16(b[1])<<8 | uint16(b[0]))
	my := int16(uint16(b[3])<<8 | uint16(b[2]))
	mz := int16(uint16(b[5])<<8 | uint16(b[4]))
	var sens float32
	if d.magBits == 16 {
		sens = 0.15
	} else {
		sens = 0.6
	}
	return float32(mx) * sens * d.magScaleX,
		float32(my) * sens * d.magScaleY,
		float32(mz) * sens * d.magScaleZ,
		nil
}

// ConfigureGyro sets the gyroscope full-scale range.
func (d *MPU9255Full) ConfigureGyro(fullScale uint8) error {
	d.gyroFs = fullScale & 0x03
	return d.writeReg(reg9255GyroConfig, (fullScale&0x03)<<3)
}

// ConfigureAccel sets the accelerometer full-scale range.
func (d *MPU9255Full) ConfigureAccel(fullScale uint8) error {
	d.accelFs = fullScale & 0x03
	return d.writeReg(reg9255AccelConfig, (fullScale&0x03)<<3)
}

// ConfigureDLPF sets the gyroscope and accelerometer DLPF bandwidths.
func (d *MPU9255Full) ConfigureDLPF(gyroDLPF, accelDLPF uint8) error {
	if err := d.writeReg(reg9255Config, gyroDLPF&0x07); err != nil {
		return err
	}
	return d.writeReg(reg9255AccelConfig2, accelDLPF&0x07)
}

// ConfigureSampleRate sets the sample rate divider.
func (d *MPU9255Full) ConfigureSampleRate(divider uint8) error {
	return d.writeReg(reg9255SmplrtDiv, divider)
}

// Temperature reads the die temperature in °C.
func (d *MPU9255Full) Temperature() (float32, error) {
	raw, err := d.readReg16Signed(reg9255TempOutH)
	if err != nil {
		return 0, err
	}
	return float32(raw)/333.87 + 21.0, nil
}

// AccelRaw reads the raw 3-axis accelerometer values.
func (d *MPU9255Full) AccelRaw() (int16, int16, int16, error) {
	b, err := d.connection.WriteRead([]byte{reg9255AccelXoutH}, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return int16(uint16(b[0])<<8 | uint16(b[1])),
		int16(uint16(b[2])<<8 | uint16(b[3])),
		int16(uint16(b[4])<<8 | uint16(b[5])),
		nil
}

// GyroRaw reads the raw 3-axis gyroscope values.
func (d *MPU9255Full) GyroRaw() (int16, int16, int16, error) {
	b, err := d.connection.WriteRead([]byte{reg9255GyroXoutH}, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return int16(uint16(b[0])<<8 | uint16(b[1])),
		int16(uint16(b[2])<<8 | uint16(b[3])),
		int16(uint16(b[4])<<8 | uint16(b[5])),
		nil
}

// MagRaw reads the raw magnetometer values.
func (d *MPU9255Full) MagRaw() (int16, int16, int16, error) {
	if !d.magEnabled {
		return 0, 0, 0, fmt.Errorf("MPU9255 MagRaw: magnetometer not enabled (call EnableMag first)")
	}
	b, err := d.magConnection.WriteRead([]byte{ak8963HXL}, 7)
	if err != nil {
		return 0, 0, 0, err
	}
	return int16(uint16(b[1])<<8 | uint16(b[0])),
		int16(uint16(b[3])<<8 | uint16(b[2])),
		int16(uint16(b[5])<<8 | uint16(b[4])),
		nil
}

// DataReady returns true when RAW_DATA_RDY_INT is set in INT_STATUS.
func (d *MPU9255Full) DataReady() (bool, error) {
	s, err := d.readReg(reg9255IntStatus)
	if err != nil {
		return false, err
	}
	return s&0x01 != 0, nil
}

// SetSleep sets or clears the SLEEP bit in PWR_MGMT_1.
func (d *MPU9255Full) SetSleep(sleep bool) error {
	v, err := d.readReg(reg9255PwrMgmt1)
	if err != nil {
		return err
	}
	if sleep {
		v |= 0x40
	} else {
		v &^= 0x40
	}
	return d.writeReg(reg9255PwrMgmt1, v)
}

// FIFOcount returns the number of bytes in the FIFO.
func (d *MPU9255Full) FIFOcount() (uint16, error) {
	b, err := d.connection.WriteRead([]byte{reg9255FifoCountH}, 2)
	if err != nil {
		return 0, err
	}
	return (uint16(b[0])&0x1F)<<8 | uint16(b[1]), nil
}

// ReadFIFO reads up to len(buf) bytes from the FIFO.
func (d *MPU9255Full) ReadFIFO(buf []byte) (uint16, error) {
	count, err := d.FIFOcount()
	if err != nil {
		return 0, err
	}
	if count == 0 {
		return 0, nil
	}
	toRead := int(count)
	if toRead > len(buf) {
		toRead = len(buf)
	}
	read, err := d.connection.WriteRead([]byte{reg9255FifoR_W}, toRead)
	if err != nil {
		return 0, err
	}
	n := copy(buf, read)
	return uint16(n), nil
}

// EnableFIFO configures and enables FIFO sources.
func (d *MPU9255Full) EnableFIFO(gyro, accel, temp bool) error {
	var v uint8
	if accel {
		v |= 1 << 3
	}
	if temp {
		v |= 1 << 2
	}
	if gyro {
		v |= 1 << 4
	}
	if err := d.writeReg(reg9255FifoEn, v); err != nil {
		return err
	}
	uc, err := d.readReg(reg9255UserCtrl)
	if err != nil {
		return err
	}
	return d.writeReg(reg9255UserCtrl, uc|0x40)
}

// ResetFIFO resets the FIFO buffer.
func (d *MPU9255Full) ResetFIFO() error {
	uc, err := d.readReg(reg9255UserCtrl)
	if err != nil {
		return err
	}
	return d.writeReg(reg9255UserCtrl, uc|0x04)
}

// lposcTable maps LP_ACCEL_ODR's Lposc_clksel field to the chip's published
// wake-up output data rate (Hz). Used by ConfigureWakeOnMotion.
var lposcTable = [16]float32{
	0.24, 0.49, 0.98, 1.95, 3.91, 7.81, 15.63, 31.25,
	62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0,
}

// ConfigureWakeOnMotion arms the hardware motion-detection logic and
// enters accelerometer-only low-power mode. Disables the gyroscope, sets
// the accelerometer DLPF to ~184 Hz, arms WOM_THR / MOT_DETECT_CTRL, and
// enables CYCLE mode in PWR_MGMT_1.
//
// thresholdMg: motion threshold in milligrams (4–1020 mg, quantized to
//              4 mg steps; values outside the range are clamped).
// odrHz:       wake-up output data rate in Hz; mapped to the chip's
//              standard 16-entry LP_ACCEL_ODR table (closest match).
func (d *MPU9255Full) ConfigureWakeOnMotion(thresholdMg uint16, odrHz float32) error {
	thresholdLsb := int((uint32(thresholdMg) + 2) / 4)
	if thresholdLsb < 1 {
		thresholdLsb = 1
	}
	if thresholdLsb > 255 {
		thresholdLsb = 255
	}
	bestSel := 0
	bestDiff := math.Abs(float64(odrHz - lposcTable[0]))
	for sel := 1; sel < len(lposcTable); sel++ {
		diff := math.Abs(float64(odrHz - lposcTable[sel]))
		if diff < bestDiff {
			bestDiff = diff
			bestSel = sel
		}
	}

	if err := d.writeReg(reg9255PwrMgmt1, 0x01); err != nil {
		return err
	}
	if err := d.writeReg(reg9255PwrMgmt2, 0x07); err != nil {
		return err
	}
	if err := d.writeReg(reg9255AccelConfig2, 0x09); err != nil {
		return err
	}
	if err := d.writeReg(reg9255IntEnable, 0x40); err != nil {
		return err
	}
	if err := d.writeReg(reg9255MotDetectCtrl, 0xC0); err != nil {
		return err
	}
	if err := d.writeReg(reg9255WomThr, byte(thresholdLsb)); err != nil {
		return err
	}
	if err := d.writeReg(reg9255LpAccelODR, byte(bestSel&0x0F)); err != nil {
		return err
	}
	return d.writeReg(reg9255PwrMgmt1, 0x21)
}

// MotionDetected returns true when WOM_INT (bit 6) is set in INT_STATUS.
// Reading INT_STATUS clears the interrupt.
func (d *MPU9255Full) MotionDetected() (bool, error) {
	s, err := d.readReg(reg9255IntStatus)
	if err != nil {
		return false, err
	}
	return s&0x40 != 0, nil
}
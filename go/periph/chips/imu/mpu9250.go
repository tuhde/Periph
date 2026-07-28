package imu

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MPU-9250 register map (subset; shared with MPU-6050 plus magnetometer
// access path).
const (
	reg9250SmplrtDiv   = 0x19
	reg9250Config      = 0x1A
	reg9250GyroConfig  = 0x1B
	reg9250AccelConfig = 0x1C
	reg9250AccelConfig2 = 0x1D
	reg9250FifoEn      = 0x23
	reg9250IntPinCfg   = 0x37
	reg9250IntStatus   = 0x3A
	reg9250AccelXoutH  = 0x3B
	reg9250TempOutH    = 0x41
	reg9250GyroXoutH   = 0x43
	reg9250UserCtrl    = 0x6A
	reg9250PwrMgmt1    = 0x6B
	reg9250FifoCountH  = 0x72
	reg9250FifoR_W     = 0x74
	reg9250WhoAmI      = 0x75

	whoAmI9250Value = 0x71
)

// AK8963 magnetometer registers (address 0x0C).
const (
	ak8963WIA   = 0x00
	ak8963ST1   = 0x02
	ak8963HXL   = 0x03
	ak8963ST2   = 0x09
	ak8963CNTL1 = 0x0A
	ak8963CNTL2 = 0x0B
	ak8963ASAX  = 0x10
	ak8963ASAY  = 0x11
	ak8963ASAZ  = 0x12

	ak8963IDExpected = 0x48

	ak8963ModePowerDown = 0x00
	ak8963ModeFuseROM   = 0x0F
	ak8963Cont100Hz16b = 0x16
)

const (
	mpu9250ResetDelay        = 100 * time.Millisecond
	mpu9250GyroStartupDelay  = 35 * time.Millisecond
	mpu9250MagPowerDownDelay = 10 * time.Millisecond
)

// MPU9250Minimal is the MPU-9250 9-axis MotionTracking device driver
// (3-axis accelerometer + 3-axis gyroscope) — minimal interface. No
// magnetometer in Minimal; see MPU9250Full for AK8963 access.
//
// Performs device reset, WHO_AM_I verification, and configures sensible
// defaults at construction. Default configuration baked in:
//   - Gyroscope full-scale: ±250 dps (GYRO_FS_SEL=0)
//   - Accelerometer full-scale: ±2 g (ACCEL_FS_SEL=0)
//   - Gyro DLPF: 41 Hz bandwidth (DLPF_CFG=3)
//   - Accel DLPF: 42 Hz bandwidth (A_DLPFCFG=3)
//   - Sample rate: 200 Hz (SMPLRT_DIV=4)
//   - Clock: auto PLL (CLKSEL=1)
type MPU9250Minimal struct {
	connection connection.Connection
	accelFs   uint8
	gyroFs    uint8
}

// NewMPU9250Minimal creates a new MPU9250Minimal, resets the device,
// verifies WHO_AM_I, and configures defaults at construction.
//
// connection must be a configured I²C connection bound to the device's
// 7-bit address (0x68 default, 0x69 alternate).
func NewMPU9250Minimal(t connection.Connection) (*MPU9250Minimal, error) {
	d := &MPU9250Minimal{connection: t}
	if err := d.writeReg(reg9250PwrMgmt1, 0x80); err != nil {
		return nil, err
	}
	time.Sleep(mpu9250ResetDelay)
	if err := d.writeReg(reg9250PwrMgmt1, 0x01); err != nil {
		return nil, err
	}
	who, err := d.readReg(reg9250WhoAmI)
	if err != nil {
		return nil, err
	}
	if who != whoAmI9250Value {
		return nil, fmt.Errorf("MPU9250 WHO_AM_I: expected 0x%02X, got 0x%02X", whoAmI9250Value, who)
	}
	if err := d.writeReg(reg9250GyroConfig, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(reg9250AccelConfig, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(reg9250AccelConfig2, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(reg9250Config, 0x03); err != nil {
		return nil, err
	}
	if err := d.writeReg(reg9250SmplrtDiv, 0x04); err != nil {
		return nil, err
	}
	time.Sleep(mpu9250GyroStartupDelay)
	return d, nil
}

func (d *MPU9250Minimal) writeReg(reg, value byte) error {
	return d.connection.Write([]byte{reg, value})
}

func (d *MPU9250Minimal) readReg(reg byte) (byte, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *MPU9250Minimal) readReg16Signed(reg byte) (int16, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return int16(uint16(b[0])<<8 | uint16(b[1])), nil
}

// Accel reads the 3-axis linear acceleration.
//
// Returns (x, y, z) in m/s².
func (d *MPU9250Minimal) Accel() (float32, float32, float32, error) {
	b, err := d.connection.WriteRead([]byte{reg9250AccelXoutH}, 6)
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
func (d *MPU9250Minimal) Gyro() (float32, float32, float32, error) {
	b, err := d.connection.WriteRead([]byte{reg9250GyroXoutH}, 6)
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

// MPU9250Full is the MPU-9250 9-axis MotionTracking device driver — full
// interface. Extends MPU9250Minimal with configuration, magnetometer
// access via the on-board AK8963, FIFO, and power control.
//
// The magnetometer is accessed via the chip's I²C bypass mode (BYPASS_EN
// in INT_PIN_CFG); the host can then address the AK8963 directly at
// 0x0C. To avoid forcing the caller to construct two connections with
// different addresses, NewMPU9250Full takes a connection factory that
// produces a new I²C connection bound to a caller-supplied 7-bit address
// on the same bus.
type MPU9250Full struct {
	*MPU9250Minimal
	magConnection connection.Connection
	magEnabled   bool
	magScaleX    float32
	magScaleY    float32
	magScaleZ    float32
	magBits      uint8
}

// ConnectionFactory returns a Connection bound to the given 7-bit address on
// the same underlying bus. Used by MPU9250Full to construct the secondary
// connection for the AK8963 magnetometer.
type ConnectionFactory func(addr uint8) (connection.Connection, error)

// NewMPU9250Full creates a new MPU9250Full with the same initialization as
// NewMPU9250Minimal. The magFactory is used to construct the AK8963
// connection on demand when EnableMag is called; it must return a
// connection bound to the given 7-bit address (0x0C for the AK8963).
func NewMPU9250Full(t connection.Connection, magFactory ConnectionFactory) (*MPU9250Full, error) {
	m, err := NewMPU9250Minimal(t)
	if err != nil {
		return nil, err
	}
	if magFactory == nil {
		return nil, fmt.Errorf("MPU9250Full: magFactory is required for AK8963 access")
	}
	mag, err := magFactory(0x0C)
	if err != nil {
		return nil, fmt.Errorf("MPU9250Full: open AK8963 connection: %w", err)
	}
	return &MPU9250Full{
		MPU9250Minimal: m,
		magConnection:   mag,
	}, nil
}

// Close releases the AK8963 secondary connection. The primary MPU9250
// connection is owned by the caller and not closed here.
func (d *MPU9250Full) Close() error {
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
func (d *MPU9250Full) EnableMag(bits, mode uint8) error {
	// 1) Enable I2C bypass so the host can address the AK8963 directly.
	if err := d.writeReg(reg9250IntPinCfg, 0x22); err != nil {
		return err
	}
	// 2) Power down the AK8963, wait 10 ms.
	if err := d.magConnection.Write([]byte{ak8963CNTL1, ak8963ModePowerDown}); err != nil {
		return err
	}
	time.Sleep(mpu9250MagPowerDownDelay)
	// 3) Enter fuse ROM access mode, wait 10 ms.
	if err := d.magConnection.Write([]byte{ak8963CNTL1, ak8963ModeFuseROM}); err != nil {
		return err
	}
	time.Sleep(mpu9250MagPowerDownDelay)
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
	time.Sleep(mpu9250MagPowerDownDelay)
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
func (d *MPU9250Full) Mag() (float32, float32, float32, error) {
	if !d.magEnabled {
		return 0, 0, 0, fmt.Errorf("MPU9250 Mag: magnetometer not enabled (call EnableMag first)")
	}
	// Read 7 bytes from 0x03 (HXL) through 0x09 (ST2). ST2 must be read
	// to unlock the next measurement.
	b, err := d.magConnection.WriteRead([]byte{ak8963HXL}, 7)
	if err != nil {
		return 0, 0, 0, err
	}
	if b[6]&0x08 != 0 {
		return 0, 0, 0, fmt.Errorf("MPU9250 Mag: AK8963 overflow")
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
func (d *MPU9250Full) ConfigureGyro(fullScale uint8) error {
	d.gyroFs = fullScale & 0x03
	return d.writeReg(reg9250GyroConfig, (fullScale&0x03)<<3)
}

// ConfigureAccel sets the accelerometer full-scale range.
func (d *MPU9250Full) ConfigureAccel(fullScale uint8) error {
	d.accelFs = fullScale & 0x03
	return d.writeReg(reg9250AccelConfig, (fullScale&0x03)<<3)
}

// ConfigureDLPF sets the gyroscope and accelerometer DLPF bandwidths.
func (d *MPU9250Full) ConfigureDLPF(gyroDLPF, accelDLPF uint8) error {
	if err := d.writeReg(reg9250Config, gyroDLPF&0x07); err != nil {
		return err
	}
	return d.writeReg(reg9250AccelConfig2, accelDLPF&0x07)
}

// ConfigureSampleRate sets the sample rate divider.
func (d *MPU9250Full) ConfigureSampleRate(divider uint8) error {
	return d.writeReg(reg9250SmplrtDiv, divider)
}

// Temperature reads the die temperature in °C.
func (d *MPU9250Full) Temperature() (float32, error) {
	raw, err := d.readReg16Signed(reg9250TempOutH)
	if err != nil {
		return 0, err
	}
	return float32(raw)/333.87 + 21.0, nil
}

// AccelRaw reads the raw 3-axis accelerometer values.
func (d *MPU9250Full) AccelRaw() (int16, int16, int16, error) {
	b, err := d.connection.WriteRead([]byte{reg9250AccelXoutH}, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return int16(uint16(b[0])<<8 | uint16(b[1])),
		int16(uint16(b[2])<<8 | uint16(b[3])),
		int16(uint16(b[4])<<8 | uint16(b[5])),
		nil
}

// GyroRaw reads the raw 3-axis gyroscope values.
func (d *MPU9250Full) GyroRaw() (int16, int16, int16, error) {
	b, err := d.connection.WriteRead([]byte{reg9250GyroXoutH}, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return int16(uint16(b[0])<<8 | uint16(b[1])),
		int16(uint16(b[2])<<8 | uint16(b[3])),
		int16(uint16(b[4])<<8 | uint16(b[5])),
		nil
}

// MagRaw reads the raw magnetometer values.
func (d *MPU9250Full) MagRaw() (int16, int16, int16, error) {
	if !d.magEnabled {
		return 0, 0, 0, fmt.Errorf("MPU9250 MagRaw: magnetometer not enabled (call EnableMag first)")
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
func (d *MPU9250Full) DataReady() (bool, error) {
	s, err := d.readReg(reg9250IntStatus)
	if err != nil {
		return false, err
	}
	return s&0x01 != 0, nil
}

// SetSleep sets or clears the SLEEP bit in PWR_MGMT_1.
func (d *MPU9250Full) SetSleep(sleep bool) error {
	v, err := d.readReg(reg9250PwrMgmt1)
	if err != nil {
		return err
	}
	if sleep {
		v |= 0x40
	} else {
		v &^= 0x40
	}
	return d.writeReg(reg9250PwrMgmt1, v)
}

// FIFOcount returns the number of bytes in the FIFO.
func (d *MPU9250Full) FIFOcount() (uint16, error) {
	b, err := d.connection.WriteRead([]byte{reg9250FifoCountH}, 2)
	if err != nil {
		return 0, err
	}
	return (uint16(b[0])&0x1F)<<8 | uint16(b[1]), nil
}

// ReadFIFO reads up to len(buf) bytes from the FIFO.
func (d *MPU9250Full) ReadFIFO(buf []byte) (uint16, error) {
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
	read, err := d.connection.WriteRead([]byte{reg9250FifoR_W}, toRead)
	if err != nil {
		return 0, err
	}
	n := copy(buf, read)
	return uint16(n), nil
}

// EnableFIFO configures and enables FIFO sources.
func (d *MPU9250Full) EnableFIFO(gyro, accel, temp bool) error {
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
	if err := d.writeReg(reg9250FifoEn, v); err != nil {
		return err
	}
	uc, err := d.readReg(reg9250UserCtrl)
	if err != nil {
		return err
	}
	return d.writeReg(reg9250UserCtrl, uc|0x40)
}

// ResetFIFO resets the FIFO buffer.
func (d *MPU9250Full) ResetFIFO() error {
	uc, err := d.readReg(reg9250UserCtrl)
	if err != nil {
		return err
	}
	return d.writeReg(reg9250UserCtrl, uc|0x04)
}

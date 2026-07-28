// Package imu contains drivers for Combined accelerometer + gyroscope IMUs.
package imu

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MPU-6050 register map (subset).
const (
	regSmplrtDiv   = 0x19
	regConfig       = 0x1A
	regGyroConfig   = 0x1B
	regAccelConfig  = 0x1C
	regFifoEn       = 0x23
	regIntStatus    = 0x3A
	regAccelXoutH   = 0x3B
	regTempOutH     = 0x41
	regGyroXoutH    = 0x43
	regUserCtrl     = 0x6A
	regPwrMgmt1     = 0x6B
	regPwrMgmt2     = 0x6C
	regFifoCountH   = 0x72
	regFifoR_W      = 0x74
	regWhoAmI       = 0x75

	whoAmIValue = 0x68
)

// accelSensitivity and gyroSensitivity are indexed by the corresponding
// full-scale selector (FS_SEL / AFS_SEL).
var (
	accelSensitivity = [4]float32{16384.0, 8192.0, 4096.0, 2048.0}
	gyroSensitivity  = [4]float32{131.0, 65.5, 32.8, 16.4}
)

// mpu6050ResetDelay is the post-DEVICE_RESET wait (datasheet Table 8: 100 ms).
const mpu6050ResetDelay = 100 * time.Millisecond

// mpu6050GyroStartupDelay is the gyroscope startup time from sleep.
const mpu6050GyroStartupDelay = 35 * time.Millisecond

// MPU6050Minimal is the MPU-6050 / MPU-6000 6-axis MotionTracking device
// driver (3-axis accelerometer + 3-axis gyroscope) — minimal interface.
//
// Performs device reset, WHO_AM_I verification, and configures sensible
// defaults at construction. Default configuration baked in:
//   - Gyroscope full-scale: ±250 dps (FS_SEL=0)
//   - Accelerometer full-scale: ±2 g (AFS_SEL=0)
//   - DLPF: 44 Hz bandwidth (DLPF_CFG=3)
//   - Sample rate: 200 Hz (SMPLRT_DIV=4)
//   - Clock: PLL with gyro X reference (CLKSEL=1)
type MPU6050Minimal struct {
	connection connection.Connection
	accelFs   uint8
	gyroFs    uint8
}

// NewMPU6050Minimal creates a new MPU6050Minimal, resets the device, verifies
// WHO_AM_I, and configures defaults at construction.
//
// connection must be a configured I²C connection bound to the device's
// 7-bit address (0x68 default, 0x69 alternate).
func NewMPU6050Minimal(t connection.Connection) (*MPU6050Minimal, error) {
	d := &MPU6050Minimal{connection: t}
	if err := d.writeReg(regPwrMgmt1, 0x80); err != nil {
		return nil, err
	}
	time.Sleep(mpu6050ResetDelay)
	if err := d.writeReg(regPwrMgmt1, 0x01); err != nil {
		return nil, err
	}
	who, err := d.readReg(regWhoAmI)
	if err != nil {
		return nil, err
	}
	if who != whoAmIValue {
		return nil, fmt.Errorf("MPU6050 WHO_AM_I: expected 0x%02X, got 0x%02X", whoAmIValue, who)
	}
	if err := d.writeReg(regGyroConfig, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(regAccelConfig, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(regConfig, 0x03); err != nil {
		return nil, err
	}
	if err := d.writeReg(regSmplrtDiv, 0x04); err != nil {
		return nil, err
	}
	time.Sleep(mpu6050GyroStartupDelay)
	return d, nil
}

// writeReg writes a single byte to the given register.
func (d *MPU6050Minimal) writeReg(reg, value byte) error {
	return d.connection.Write([]byte{reg, value})
}

// readReg reads a single byte from the given register.
func (d *MPU6050Minimal) readReg(reg byte) (byte, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// readReg16Signed reads a big-endian signed 16-bit register value.
func (d *MPU6050Minimal) readReg16Signed(reg byte) (int16, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return int16(uint16(b[0])<<8 | uint16(b[1])), nil
}

// Accel reads the 3-axis linear acceleration.
//
// Returns (x, y, z) in m/s².
func (d *MPU6050Minimal) Accel() (float32, float32, float32, error) {
	b, err := d.connection.WriteRead([]byte{regAccelXoutH}, 6)
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
func (d *MPU6050Minimal) Gyro() (float32, float32, float32, error) {
	b, err := d.connection.WriteRead([]byte{regGyroXoutH}, 6)
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

// MPU6050Full is the MPU-6050 / MPU-6000 driver — full interface.
//
// Extends MPU6050Minimal with full-scale range configuration, DLPF
// settings, sample rate control, temperature reading, raw data access,
// data-ready polling, sleep/standby control, and FIFO management.
//
// Embeds MPU6050Minimal to inherit Accel, Gyro, and the constructor.
type MPU6050Full struct {
	*MPU6050Minimal
}

// NewMPU6050Full creates a new MPU6050Full with the same initialization as
// NewMPU6050Minimal.
func NewMPU6050Full(t connection.Connection) (*MPU6050Full, error) {
	m, err := NewMPU6050Minimal(t)
	if err != nil {
		return nil, err
	}
	return &MPU6050Full{MPU6050Minimal: m}, nil
}

// ConfigureGyro sets the gyroscope full-scale range.
//
// full_scale selects the range: 0=±250, 1=±500, 2=±1000, 3=±2000 dps.
func (d *MPU6050Full) ConfigureGyro(fullScale uint8) error {
	d.gyroFs = fullScale & 0x03
	return d.writeReg(regGyroConfig, (fullScale&0x03)<<3)
}

// ConfigureAccel sets the accelerometer full-scale range.
//
// full_scale selects the range: 0=±2g, 1=±4g, 2=±8g, 3=±16g.
func (d *MPU6050Full) ConfigureAccel(fullScale uint8) error {
	d.accelFs = fullScale & 0x03
	return d.writeReg(regAccelConfig, (fullScale&0x03)<<3)
}

// ConfigureDLPF sets the digital low-pass filter bandwidth.
//
// dlpf selects the bandwidth: 0=260/256 Hz, 1=184/188 Hz, 2=94/98 Hz,
// 3=44/42 Hz, 4=21/20 Hz, 5=10/10 Hz, 6=5/5 Hz (gyro/accel BW).
func (d *MPU6050Full) ConfigureDLPF(dlpf uint8) error {
	return d.writeReg(regConfig, dlpf&0x07)
}

// ConfigureSampleRate sets the sample rate divider.
//
// divider is the SMPLRT_DIV value (0–255); output rate = 1 kHz / (1 + divider)
// when DLPF is active.
func (d *MPU6050Full) ConfigureSampleRate(divider uint8) error {
	return d.writeReg(regSmplrtDiv, divider)
}

// Temperature reads the die temperature.
//
// Returns the temperature in °C.
func (d *MPU6050Full) Temperature() (float32, error) {
	raw, err := d.readReg16Signed(regTempOutH)
	if err != nil {
		return 0, err
	}
	return float32(raw)/340.0 + 36.53, nil
}

// AccelRaw reads the raw 3-axis accelerometer values.
//
// Returns (x, y, z) as raw 16-bit signed values.
func (d *MPU6050Full) AccelRaw() (int16, int16, int16, error) {
	b, err := d.connection.WriteRead([]byte{regAccelXoutH}, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return int16(uint16(b[0])<<8 | uint16(b[1])),
		int16(uint16(b[2])<<8 | uint16(b[3])),
		int16(uint16(b[4])<<8 | uint16(b[5])),
		nil
}

// GyroRaw reads the raw 3-axis gyroscope values.
//
// Returns (x, y, z) as raw 16-bit signed values.
func (d *MPU6050Full) GyroRaw() (int16, int16, int16, error) {
	b, err := d.connection.WriteRead([]byte{regGyroXoutH}, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return int16(uint16(b[0])<<8 | uint16(b[1])),
		int16(uint16(b[2])<<8 | uint16(b[3])),
		int16(uint16(b[4])<<8 | uint16(b[5])),
		nil
}

// DataReady returns true when DATA_RDY_INT is set in INT_STATUS.
func (d *MPU6050Full) DataReady() (bool, error) {
	s, err := d.readReg(regIntStatus)
	if err != nil {
		return false, err
	}
	return s&0x01 != 0, nil
}

// SetSleep sets or clears the SLEEP bit in PWR_MGMT_1.
//
// sleep=true enters sleep mode; sleep=false wakes the device.
func (d *MPU6050Full) SetSleep(sleep bool) error {
	v, err := d.readReg(regPwrMgmt1)
	if err != nil {
		return err
	}
	if sleep {
		v |= 0x40
	} else {
		v &^= 0x40
	}
	return d.writeReg(regPwrMgmt1, v)
}

// SetStandby puts individual axes into standby mode via PWR_MGMT_2.
func (d *MPU6050Full) SetStandby(xa, ya, za, xg, yg, zg bool) error {
	var v uint8
	if xa {
		v |= 1 << 5
	}
	if ya {
		v |= 1 << 4
	}
	if za {
		v |= 1 << 3
	}
	if xg {
		v |= 1 << 2
	}
	if yg {
		v |= 1 << 1
	}
	if zg {
		v |= 1
	}
	return d.writeReg(regPwrMgmt2, v)
}

// FIFOcount returns the number of bytes currently in the FIFO (0–1024).
func (d *MPU6050Full) FIFOcount() (uint16, error) {
	b, err := d.connection.WriteRead([]byte{regFifoCountH}, 2)
	if err != nil {
		return 0, err
	}
	return (uint16(b[0])&0x1F)<<8 | uint16(b[1]), nil
}

// ReadFIFO reads up to len(buf) bytes from the FIFO and returns the number
// of bytes actually read.
func (d *MPU6050Full) ReadFIFO(buf []byte) (uint16, error) {
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
	read, err := d.connection.WriteRead([]byte{regFifoR_W}, toRead)
	if err != nil {
		return 0, err
	}
	n := copy(buf, read)
	return uint16(n), nil
}

// EnableFIFO configures and enables FIFO sources.
func (d *MPU6050Full) EnableFIFO(gyro, accel, temp bool) error {
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
	if err := d.writeReg(regFifoEn, v); err != nil {
		return err
	}
	uc, err := d.readReg(regUserCtrl)
	if err != nil {
		return err
	}
	return d.writeReg(regUserCtrl, uc|0x40)
}

// ResetFIFO resets the FIFO buffer by setting FIFO_RST in USER_CTRL.
func (d *MPU6050Full) ResetFIFO() error {
	uc, err := d.readReg(regUserCtrl)
	if err != nil {
		return err
	}
	return d.writeReg(regUserCtrl, uc|0x04)
}

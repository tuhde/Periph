package imu

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ICM-20948 register map. The registers are organised in four user banks
// selected through REG_BANK_SEL (0x7F); the bank of each constant is noted.
const (
	// Bank 0
	reg20948WhoAmI         = 0x00
	reg20948UserCtrl       = 0x03
	reg20948LpConfig       = 0x05
	reg20948PwrMgmt1       = 0x06
	reg20948PwrMgmt2       = 0x07
	reg20948IntPinCfg      = 0x0F
	reg20948IntEnable      = 0x10
	reg20948IntEnable1     = 0x11
	reg20948IntEnable2     = 0x12
	reg20948I2cMstStatus   = 0x17
	reg20948IntStatus      = 0x19
	reg20948IntStatus1     = 0x1A
	reg20948AccelXoutH     = 0x2D
	reg20948GyroXoutH      = 0x33
	reg20948TempOutH       = 0x39
	reg20948ExtSlvSensData = 0x3B
	reg20948FifoEn2        = 0x67
	reg20948FifoRst        = 0x68
	reg20948FifoMode       = 0x69
	reg20948FifoCountH     = 0x70
	reg20948FifoRW         = 0x72
	reg20948BankSel        = 0x7F

	// Bank 1
	reg20948XaOffsH            = 0x14
	reg20948TimebaseCorrection = 0x28

	// Bank 2
	reg20948GyroSmplrtDiv   = 0x00
	reg20948GyroConfig1     = 0x01
	reg20948GyroConfig2     = 0x02
	reg20948XgOffsUsrH      = 0x03
	reg20948AccelSmplrtDiv1 = 0x10
	reg20948AccelSmplrtDiv2 = 0x11
	reg20948AccelIntelCtrl  = 0x12
	reg20948AccelWomThr     = 0x13
	reg20948AccelConfig     = 0x14
	reg20948AccelConfig2    = 0x15

	// Bank 3
	reg20948I2cMstCtrl  = 0x01
	reg20948I2cSlv0Addr = 0x03
	reg20948I2cSlv0Reg  = 0x04
	reg20948I2cSlv0Ctrl = 0x05
	reg20948I2cSlv4Addr = 0x13
	reg20948I2cSlv4Reg  = 0x14
	reg20948I2cSlv4Ctrl = 0x15
	reg20948I2cSlv4Do   = 0x16
	reg20948I2cSlv4Di   = 0x17

	whoAmI20948Value = 0xEA
)

// AK09916 magnetometer (address 0x0C on the auxiliary I²C bus).
const (
	ak09916Addr      = 0x0C
	ak09916WIA2      = 0x01
	ak09916ST1       = 0x10
	ak09916CNTL2     = 0x31
	ak09916CNTL3     = 0x32
	ak09916WIA2Value = 0x09

	icm20948MagSensitivity = 0.15 // µT per LSB
)

// ICM20948 interrupt source bits, returned by PollInterrupt and accepted by
// EnableInterrupt / DisableInterrupt.
const (
	ICM20948SourceDataReady    uint8 = 0x01
	ICM20948SourceWakeOnMotion uint8 = 0x02
	ICM20948SourceI2CMaster    uint8 = 0x04
	ICM20948SourcePLLReady     uint8 = 0x08
	ICM20948SourceFifoOverflow uint8 = 0x10
)

const (
	icm20948ResetDelay       = 100 * time.Millisecond
	icm20948GyroStartupDelay = 35 * time.Millisecond
)

var (
	icm20948AccelSensitivity = [4]float32{16384.0, 8192.0, 4096.0, 2048.0}
	icm20948GyroSensitivity  = [4]float32{131.0, 65.5, 32.8, 16.4}
)

const (
	icm20948G      = 9.80665
	icm20948DegRad = math.Pi / 180.0
)

// ICM20948Minimal is the ICM-20948 9-axis MotionTracking device driver
// (TDK InvenSense) — minimal interface: 3-axis acceleration and 3-axis
// angular rate. The magnetometer is part of ICM20948Full.
//
// The registers are organised in four user banks; the driver caches the
// selected bank and always returns to bank 0 after a bank 1–3 access.
// VDDIO is 1.71–1.95 V only. SPI and the DMP are out of scope.
//
// Default configuration baked in:
//   - Gyroscope ±250 dps, low-pass filter 51.2 Hz (GYRO_DLPFCFG = 3)
//   - Accelerometer ±2 g, low-pass filter 50.4 Hz (ACCEL_DLPFCFG = 3)
//   - Both output data rates 102.3 Hz (divider 10)
//   - Clock auto (CLKSEL = 1), all six axes and the temperature sensor on
//   - FIFO, DMP, I²C master and all interrupts off
type ICM20948Minimal struct {
	conn        connection.RegisterConnection
	bank        uint8
	accelFs     uint8
	gyroFs      uint8
	accelDlpf   uint8
	gyroDlpf    uint8
	accelFilter bool
	gyroFilter  bool
	lpEn        bool
	lpSuspended bool
}

// NewICM20948Minimal verifies WHO_AM_I, resets the device and configures the
// defaults. conn must be a configured I²C connection bound to the device's
// 7-bit address (0x68 with AD0 = GND, 0x69 with AD0 = VDDIO).
func NewICM20948Minimal(conn connection.RegisterConnection) (*ICM20948Minimal, error) {
	d := &ICM20948Minimal{conn: conn, accelDlpf: 3, gyroDlpf: 3, accelFilter: true, gyroFilter: true}
	if err := d.selectBank(0, true); err != nil {
		return nil, err
	}
	who, err := d.rd1(0, reg20948WhoAmI)
	if err != nil {
		return nil, err
	}
	if who != whoAmI20948Value {
		return nil, fmt.Errorf("ICM20948 WHO_AM_I: expected 0x%02X, got 0x%02X", whoAmI20948Value, who)
	}
	if err := d.wr1(0, reg20948PwrMgmt1, 0x80); err != nil { // DEVICE_RESET
		return nil, err
	}
	time.Sleep(icm20948ResetDelay)
	d.bank = 0                                               // reset returns to bank 0
	if err := d.wr1(0, reg20948PwrMgmt1, 0x01); err != nil { // SLEEP = 0, CLKSEL = 1
		return nil, err
	}
	if err := d.wr1(0, reg20948PwrMgmt2, 0x00); err != nil { // all axes on
		return nil, err
	}
	if err := d.writeGyroConfig(); err != nil {
		return nil, err
	}
	if err := d.writeAccelConfig(); err != nil {
		return nil, err
	}
	for _, w := range [][2]byte{{reg20948GyroSmplrtDiv, 0x0A}, {reg20948AccelSmplrtDiv1, 0x00}, {reg20948AccelSmplrtDiv2, 0x0A}} {
		if err := d.wr1(2, w[0], w[1]); err != nil {
			return nil, err
		}
	}
	time.Sleep(icm20948GyroStartupDelay)
	return d, nil
}

func (d *ICM20948Minimal) selectBank(bank uint8, force bool) error {
	if force || bank != d.bank {
		if err := d.conn.WriteReg(reg20948BankSel, []byte{bank << 4}); err != nil {
			return err
		}
		d.bank = bank
	}
	return nil
}

func (d *ICM20948Minimal) rd(bank uint8, reg byte, length int) ([]byte, error) {
	if err := d.selectBank(bank, false); err != nil {
		return nil, err
	}
	b, err := d.conn.ReadReg(uint32(reg), length)
	if err != nil {
		return nil, err
	}
	if bank != 0 {
		if err := d.selectBank(0, false); err != nil {
			return nil, err
		}
	}
	return b, nil
}

func (d *ICM20948Minimal) wr(bank uint8, reg byte, data []byte) error {
	if err := d.selectBank(bank, false); err != nil {
		return err
	}
	if err := d.conn.WriteReg(uint32(reg), data); err != nil {
		return err
	}
	if bank != 0 {
		return d.selectBank(0, false)
	}
	return nil
}

func (d *ICM20948Minimal) rd1(bank uint8, reg byte) (byte, error) {
	b, err := d.rd(bank, reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *ICM20948Minimal) wr1(bank uint8, reg, value byte) error {
	return d.wr(bank, reg, []byte{value})
}

func (d *ICM20948Minimal) writeGyroConfig() error {
	v := d.gyroDlpf<<3 | d.gyroFs<<1
	if d.gyroFilter {
		v |= 1
	}
	return d.wr1(2, reg20948GyroConfig1, v)
}

func (d *ICM20948Minimal) writeAccelConfig() error {
	v := d.accelDlpf<<3 | d.accelFs<<1
	if d.accelFilter {
		v |= 1
	}
	return d.wr1(2, reg20948AccelConfig, v)
}

func be16(b []byte) int16 { return int16(uint16(b[0])<<8 | uint16(b[1])) }
func le16(b []byte) int16 { return int16(uint16(b[1])<<8 | uint16(b[0])) }

// Accel reads the 3-axis linear acceleration. Returns (x, y, z) in m/s².
func (d *ICM20948Minimal) Accel() (float32, float32, float32, error) {
	b, err := d.rd(0, reg20948AccelXoutH, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	sens := icm20948AccelSensitivity[d.accelFs]
	return float32(be16(b)) / sens * icm20948G, float32(be16(b[2:])) / sens * icm20948G,
		float32(be16(b[4:])) / sens * icm20948G, nil
}

// Gyro reads the 3-axis angular rate. Returns (x, y, z) in rad/s.
func (d *ICM20948Minimal) Gyro() (float32, float32, float32, error) {
	b, err := d.rd(0, reg20948GyroXoutH, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	sens := icm20948GyroSensitivity[d.gyroFs]
	return float32(be16(b)) / sens * icm20948DegRad, float32(be16(b[2:])) / sens * icm20948DegRad,
		float32(be16(b[4:])) / sens * icm20948DegRad, nil
}

// ICM20948Full extends ICM20948Minimal with the complete chip functionality:
// raw and burst reads, temperature, full-scale / filter / sample-rate
// configuration, sensor power control, low-power modes, gyro and
// accelerometer offsets, the AK09916 magnetometer (read through the chip's own
// I²C master — no second connection is needed), single-byte auxiliary-bus
// access, the 4 KiB FIFO, wake-on-motion, interrupts, self-test and the PLL
// time-base correction.
//
// Methods that touch banks 1–3 select the bank and return to bank 0, so the
// bank register is 0 between calls.
type ICM20948Full struct {
	*ICM20948Minimal
	magEnabled bool

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	activeLow   bool
}

// NewICM20948Full creates the Full driver; conn is as for NewICM20948Minimal.
func NewICM20948Full(conn connection.RegisterConnection) (*ICM20948Full, error) {
	m, err := NewICM20948Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &ICM20948Full{ICM20948Minimal: m}, nil
}

func (d *ICM20948Full) lpSuspend() error {
	if d.lpEn && !d.lpSuspended {
		v, err := d.rd1(0, reg20948PwrMgmt1)
		if err != nil {
			return err
		}
		if err := d.wr1(0, reg20948PwrMgmt1, v&^0x20); err != nil {
			return err
		}
		d.lpSuspended = true
	}
	return nil
}

func (d *ICM20948Full) lpResume() error {
	if d.lpSuspended {
		v, err := d.rd1(0, reg20948PwrMgmt1)
		if err != nil {
			return err
		}
		if err := d.wr1(0, reg20948PwrMgmt1, v|0x20); err != nil {
			return err
		}
		d.lpSuspended = false
	}
	return nil
}

// AccelRaw reads the raw 3-axis accelerometer values (signed 16-bit counts).
func (d *ICM20948Full) AccelRaw() (int16, int16, int16, error) {
	b, err := d.rd(0, reg20948AccelXoutH, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return be16(b), be16(b[2:]), be16(b[4:]), nil
}

// GyroRaw reads the raw 3-axis gyroscope values (signed 16-bit counts).
func (d *ICM20948Full) GyroRaw() (int16, int16, int16, error) {
	b, err := d.rd(0, reg20948GyroXoutH, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return be16(b), be16(b[2:]), be16(b[4:]), nil
}

// ReadAll burst-reads acceleration, angular rate and temperature so all three
// belong to one sample. Returns accel (m/s²), gyro (rad/s) and the die
// temperature (°C).
func (d *ICM20948Full) ReadAll() (accel [3]float32, gyro [3]float32, tempC float32, err error) {
	b, err := d.rd(0, reg20948AccelXoutH, 14)
	if err != nil {
		return accel, gyro, 0, err
	}
	as := icm20948AccelSensitivity[d.accelFs]
	gs := icm20948GyroSensitivity[d.gyroFs]
	for i := 0; i < 3; i++ {
		accel[i] = float32(be16(b[2*i:])) / as * icm20948G
		gyro[i] = float32(be16(b[6+2*i:])) / gs * icm20948DegRad
	}
	return accel, gyro, float32(be16(b[12:]))/333.87 + 21.0, nil
}

// Temperature reads the die temperature in °C.
func (d *ICM20948Full) Temperature() (float32, error) {
	b, err := d.rd(0, reg20948TempOutH, 2)
	if err != nil {
		return 0, err
	}
	return float32(be16(b))/333.87 + 21.0, nil
}

// ConfigureGyro sets the gyroscope full-scale range: 0 = ±250, 1 = ±500,
// 2 = ±1000, 3 = ±2000 dps.
func (d *ICM20948Full) ConfigureGyro(fullScale uint8) error {
	if err := d.lpSuspend(); err != nil {
		return err
	}
	d.gyroFs = fullScale & 0x03
	if err := d.writeGyroConfig(); err != nil {
		return err
	}
	return d.lpResume()
}

// ConfigureAccel sets the accelerometer full-scale range: 0 = ±2, 1 = ±4,
// 2 = ±8, 3 = ±16 g.
func (d *ICM20948Full) ConfigureAccel(fullScale uint8) error {
	if err := d.lpSuspend(); err != nil {
		return err
	}
	d.accelFs = fullScale & 0x03
	if err := d.writeAccelConfig(); err != nil {
		return err
	}
	return d.lpResume()
}

// ConfigureFilters sets the digital low-pass filters. gyroDlpf and accelDlpf
// are the *_DLPFCFG values 0–7 (3 = 51.2 Hz gyro / 50.4 Hz accel); a false
// filter flag bypasses that filter (higher output rate).
func (d *ICM20948Full) ConfigureFilters(gyroDlpf, accelDlpf uint8, gyroFilter, accelFilter bool) error {
	if err := d.lpSuspend(); err != nil {
		return err
	}
	d.gyroDlpf = gyroDlpf & 0x07
	d.accelDlpf = accelDlpf & 0x07
	d.gyroFilter = gyroFilter
	d.accelFilter = accelFilter
	if err := d.writeGyroConfig(); err != nil {
		return err
	}
	if err := d.writeAccelConfig(); err != nil {
		return err
	}
	return d.lpResume()
}

// ConfigureSampleRate sets the sample-rate dividers; ODR = 1125 Hz / (1 + div).
// gyroDiv is 0–255, accelDiv is 0–4095.
func (d *ICM20948Full) ConfigureSampleRate(gyroDiv uint8, accelDiv uint16) error {
	if err := d.lpSuspend(); err != nil {
		return err
	}
	if err := d.wr1(2, reg20948GyroSmplrtDiv, gyroDiv); err != nil {
		return err
	}
	if err := d.wr1(2, reg20948AccelSmplrtDiv1, byte(accelDiv>>8)&0x0F); err != nil {
		return err
	}
	if err := d.wr1(2, reg20948AccelSmplrtDiv2, byte(accelDiv&0xFF)); err != nil {
		return err
	}
	return d.lpResume()
}

// SetSensors switches the accelerometer and gyroscope on or off
// (PWR_MGMT_2). Turning a sensor on again needs its start-up time (accel
// 20 ms, gyro 35 ms).
func (d *ICM20948Full) SetSensors(accel, gyro bool) error {
	var v byte
	if !accel {
		v |= 0x38
	}
	if !gyro {
		v |= 0x07
	}
	return d.wr1(0, reg20948PwrMgmt2, v)
}

// SetSleep sets or clears the SLEEP bit in PWR_MGMT_1.
func (d *ICM20948Full) SetSleep(sleep bool) error {
	v, err := d.rd1(0, reg20948PwrMgmt1)
	if err != nil {
		return err
	}
	if sleep {
		v |= 0x40
	} else {
		v &^= 0x40
	}
	return d.wr1(0, reg20948PwrMgmt1, v)
}

// SetLowPower configures duty-cycled low-power operation. accelAvg is the
// DEC3_CFG averaging code 0–3 and gyroAvg the GYRO_AVGCFG code 0–7. LP_EN is
// set last because it blocks most register writes.
func (d *ICM20948Full) SetLowPower(accel, gyro bool, accelAvg, gyroAvg uint8) error {
	d.lpSuspended = false
	v, err := d.rd1(0, reg20948PwrMgmt1)
	if err != nil {
		return err
	}
	v &^= 0x20
	if err := d.wr1(0, reg20948PwrMgmt1, v); err != nil {
		return err
	}
	if err := d.wr1(2, reg20948AccelConfig2, accelAvg&0x03); err != nil {
		return err
	}
	if err := d.wr1(2, reg20948GyroConfig2, gyroAvg&0x07); err != nil {
		return err
	}
	lp := byte(0x40)
	if accel {
		lp |= 0x20
	}
	if gyro {
		lp |= 0x10
	}
	if err := d.wr1(0, reg20948LpConfig, lp); err != nil {
		return err
	}
	d.lpEn = accel || gyro
	if d.lpEn {
		return d.wr1(0, reg20948PwrMgmt1, v|0x20)
	}
	return nil
}

// SetGyroOffset writes the gyroscope offset-cancellation registers; x, y and
// z are in rad/s (0.0305 dps/LSB, signed 16-bit).
func (d *ICM20948Full) SetGyroOffset(x, y, z float32) error {
	data := make([]byte, 6)
	for i, v := range [3]float32{x, y, z} {
		code := math.Round(float64(v) / icm20948DegRad / 0.0305)
		code = math.Max(-32768, math.Min(32767, code))
		u := uint16(int16(code))
		data[2*i] = byte(u >> 8)
		data[2*i+1] = byte(u)
	}
	if err := d.lpSuspend(); err != nil {
		return err
	}
	if err := d.wr(2, reg20948XgOffsUsrH, data); err != nil {
		return err
	}
	return d.lpResume()
}

// GetAccelOffset reads the 15-bit accelerometer offset codes (0.98 mg/LSB).
// The registers hold a per-part factory trim: read, adjust, write back.
func (d *ICM20948Full) GetAccelOffset() (int16, int16, int16, error) {
	raw, err := d.rd(1, reg20948XaOffsH, 8)
	if err != nil {
		return 0, 0, 0, err
	}
	code := func(i int) int16 {
		c := int32(raw[i])<<7 | int32(raw[i+1])>>1
		if c&0x4000 != 0 {
			c -= 0x8000
		}
		return int16(c)
	}
	return code(0), code(3), code(6), nil
}

// SetAccelOffset writes the 15-bit accelerometer offset codes.
func (d *ICM20948Full) SetAccelOffset(x, y, z int16) error {
	if err := d.lpSuspend(); err != nil {
		return err
	}
	regs := [3]byte{0x14, 0x17, 0x1A}
	for i, v := range [3]int16{x, y, z} {
		code := uint16(v) & 0x7FFF
		if err := d.wr(1, regs[i], []byte{byte(code >> 7), byte(code<<1) & 0xFE}); err != nil {
			return err
		}
	}
	return d.lpResume()
}

func (d *ICM20948Full) slv4Wait() error {
	for i := 0; i < 100; i++ {
		status, err := d.rd1(0, reg20948I2cMstStatus)
		if err != nil {
			return err
		}
		if status&0x10 != 0 {
			return errors.New("ICM20948 auxiliary I2C NACK")
		}
		if status&0x40 != 0 {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return errors.New("ICM20948 auxiliary I2C transfer timed out")
}

// AuxReadByte reads one byte from an auxiliary-bus device through I²C-master
// slave 4. It fails on a NACK or timeout.
func (d *ICM20948Full) AuxReadByte(address, register byte) (byte, error) {
	if err := d.selectBank(3, false); err != nil {
		return 0, err
	}
	for _, w := range [][2]byte{{reg20948I2cSlv4Addr, 0x80 | (address & 0x7F)}, {reg20948I2cSlv4Reg, register}, {reg20948I2cSlv4Ctrl, 0x80}} {
		if err := d.conn.WriteReg(uint32(w[0]), []byte{w[1]}); err != nil {
			return 0, err
		}
	}
	if err := d.selectBank(0, false); err != nil {
		return 0, err
	}
	if err := d.slv4Wait(); err != nil {
		return 0, err
	}
	return d.rd1(3, reg20948I2cSlv4Di)
}

// AuxWriteByte writes one byte to an auxiliary-bus device through I²C-master
// slave 4. It fails on a NACK or timeout.
func (d *ICM20948Full) AuxWriteByte(address, register, value byte) error {
	if err := d.selectBank(3, false); err != nil {
		return err
	}
	for _, w := range [][2]byte{{reg20948I2cSlv4Addr, address & 0x7F}, {reg20948I2cSlv4Reg, register}, {reg20948I2cSlv4Do, value}, {reg20948I2cSlv4Ctrl, 0x80}} {
		if err := d.conn.WriteReg(uint32(w[0]), []byte{w[1]}); err != nil {
			return err
		}
	}
	if err := d.selectBank(0, false); err != nil {
		return err
	}
	return d.slv4Wait()
}

// EnableMag enables the AK09916 magnetometer via the I²C master. It resets
// the magnetometer, verifies WIA2 == 0x09, selects continuous measurement 4
// (100 Hz) and configures slave 0 to copy the 9-byte ST1..ST2 block to
// EXT_SLV_SENS_DATA_00..08 continuously.
func (d *ICM20948Full) EnableMag() error {
	if err := d.lpSuspend(); err != nil {
		return err
	}
	ctrl, err := d.rd1(0, reg20948UserCtrl)
	if err != nil {
		return err
	}
	if err := d.wr1(0, reg20948UserCtrl, ctrl|0x20); err != nil { // I2C_MST_EN
		return err
	}
	if err := d.wr1(3, reg20948I2cMstCtrl, 0x07); err != nil { // 345.6 kHz
		return err
	}
	if err := d.AuxWriteByte(ak09916Addr, ak09916CNTL3, 0x01); err != nil { // soft reset
		return err
	}
	time.Sleep(2 * time.Millisecond)
	wia, err := d.AuxReadByte(ak09916Addr, ak09916WIA2)
	if err != nil {
		return err
	}
	if wia != ak09916WIA2Value {
		return fmt.Errorf("AK09916 WIA2: expected 0x%02X, got 0x%02X", ak09916WIA2Value, wia)
	}
	if err := d.AuxWriteByte(ak09916Addr, ak09916CNTL2, 0x08); err != nil { // continuous 4
		return err
	}
	if err := d.selectBank(3, false); err != nil {
		return err
	}
	for _, w := range [][2]byte{{reg20948I2cSlv0Addr, 0x80 | ak09916Addr}, {reg20948I2cSlv0Reg, ak09916ST1}, {reg20948I2cSlv0Ctrl, 0x89}} {
		if err := d.conn.WriteReg(uint32(w[0]), []byte{w[1]}); err != nil {
			return err
		}
	}
	if err := d.selectBank(0, false); err != nil {
		return err
	}
	d.magEnabled = true
	return d.lpResume()
}

// MagSetMode writes the magnetometer CNTL2 mode through slave 4: 0
// power-down, 1 single, 2/4/6 continuous 1–3, 8 continuous 4.
func (d *ICM20948Full) MagSetMode(mode byte) error {
	return d.AuxWriteByte(ak09916Addr, ak09916CNTL2, mode&0x1F)
}

func (d *ICM20948Full) magBlock() ([]byte, error) {
	if !d.magEnabled {
		return nil, errors.New("magnetometer not enabled; call EnableMag first")
	}
	return d.rd(0, reg20948ExtSlvSensData, 9)
}

// Mag reads the magnetic field in µT, as the chip reports it. It fails if
// EnableMag has not been called or the magnetic sensor overflowed (HOFL).
func (d *ICM20948Full) Mag() (float32, float32, float32, error) {
	b, err := d.magBlock()
	if err != nil {
		return 0, 0, 0, err
	}
	if b[8]&0x08 != 0 {
		return 0, 0, 0, errors.New("magnetometer overflow (HOFL)")
	}
	return float32(le16(b[1:])) * icm20948MagSensitivity, float32(le16(b[3:])) * icm20948MagSensitivity,
		float32(le16(b[5:])) * icm20948MagSensitivity, nil
}

// MagRaw reads the raw magnetometer values (signed 16-bit counts).
func (d *ICM20948Full) MagRaw() (int16, int16, int16, error) {
	b, err := d.magBlock()
	if err != nil {
		return 0, 0, 0, err
	}
	return le16(b[1:]), le16(b[3:]), le16(b[5:]), nil
}

// MagDataReady reports the magnetometer DRDY flag (ST1) from the shadowed block.
func (d *ICM20948Full) MagDataReady() (bool, error) {
	b, err := d.magBlock()
	if err != nil {
		return false, err
	}
	return b[0]&0x01 != 0, nil
}

// DataReady reports INT_STATUS_1.RAW_DATA_0_RDY_INT.
func (d *ICM20948Full) DataReady() (bool, error) {
	v, err := d.rd1(0, reg20948IntStatus1)
	return v&0x01 != 0, err
}

// EnableFifo selects the FIFO sources, enables the FIFO and resets it.
func (d *ICM20948Full) EnableFifo(accel, gyro, temp bool) error {
	if err := d.lpSuspend(); err != nil {
		return err
	}
	var src byte
	if accel {
		src |= 0x10
	}
	if gyro {
		src |= 0x0E
	}
	if temp {
		src |= 0x01
	}
	if err := d.wr1(0, reg20948FifoEn2, src); err != nil {
		return err
	}
	ctrl, err := d.rd1(0, reg20948UserCtrl)
	if err != nil {
		return err
	}
	if err := d.wr1(0, reg20948UserCtrl, ctrl|0x40); err != nil {
		return err
	}
	if err := d.ResetFifo(); err != nil {
		return err
	}
	return d.lpResume()
}

// SetFifoMode selects the FIFO mode: false = stream (overwrite oldest),
// true = snapshot (stop when full).
func (d *ICM20948Full) SetFifoMode(snapshot bool) error {
	if err := d.lpSuspend(); err != nil {
		return err
	}
	v := byte(0x00)
	if snapshot {
		v = 0x1F
	}
	if err := d.wr1(0, reg20948FifoMode, v); err != nil {
		return err
	}
	return d.lpResume()
}

// FifoCount reads the number of bytes in the FIFO (0–4096).
func (d *ICM20948Full) FifoCount() (uint16, error) {
	b, err := d.rd(0, reg20948FifoCountH, 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0]&0x1F)<<8 | uint16(b[1]), nil
}

// ReadFifo reads up to count bytes from the FIFO (all available bytes when
// count is negative). The frame layout is not documented in the datasheet.
func (d *ICM20948Full) ReadFifo(count int) ([]byte, error) {
	available, err := d.FifoCount()
	if err != nil {
		return nil, err
	}
	if count < 0 || count > int(available) {
		count = int(available)
	}
	out := make([]byte, 0, count)
	for count > 0 {
		n := count
		if n > 24 {
			n = 24
		}
		b, err := d.rd(0, reg20948FifoRW, n)
		if err != nil {
			return nil, err
		}
		out = append(out, b...)
		count -= n
	}
	return out, nil
}

// ResetFifo resets the FIFO by asserting and de-asserting FIFO_RST.
func (d *ICM20948Full) ResetFifo() error {
	if err := d.wr1(0, reg20948FifoRst, 0x1F); err != nil {
		return err
	}
	return d.wr1(0, reg20948FifoRst, 0x00)
}

// SetWakeOnMotion configures wake-on-motion (needs the accelerometer on with
// its filter enabled). thresholdMg is in mg (4 mg/LSB, 0–1020).
func (d *ICM20948Full) SetWakeOnMotion(thresholdMg float32, enabled bool) error {
	if err := d.lpSuspend(); err != nil {
		return err
	}
	thr := math.Max(0, math.Min(255, math.Round(float64(thresholdMg)/4)))
	if err := d.wr1(2, reg20948AccelWomThr, byte(thr)); err != nil {
		return err
	}
	ctrl := byte(0x00)
	if enabled {
		ctrl = 0x03
	}
	if err := d.wr1(2, reg20948AccelIntelCtrl, ctrl); err != nil {
		return err
	}
	var err error
	if enabled {
		err = d.EnableInterrupt(ICM20948SourceWakeOnMotion)
	} else {
		err = d.DisableInterrupt(ICM20948SourceWakeOnMotion)
	}
	if err != nil {
		return err
	}
	return d.lpResume()
}

// SetInterruptPin configures the INT1 pin (INT_PIN_CFG).
func (d *ICM20948Full) SetInterruptPin(activeLow, openDrain, latch, anyReadClears bool) error {
	var v byte
	if activeLow {
		v |= 0x80
	}
	if openDrain {
		v |= 0x40
	}
	if latch {
		v |= 0x20
	}
	if anyReadClears {
		v |= 0x10
	}
	d.mu.Lock()
	d.activeLow = activeLow
	d.mu.Unlock()
	return d.wr1(0, reg20948IntPinCfg, v)
}

func (d *ICM20948Full) intEnable(source uint8, on bool) error {
	var reg, mask byte
	switch source {
	case ICM20948SourceDataReady:
		reg, mask = reg20948IntEnable1, 0x01
	case ICM20948SourceWakeOnMotion:
		reg, mask = reg20948IntEnable, 0x08
	case ICM20948SourceI2CMaster:
		reg, mask = reg20948IntEnable, 0x01
	case ICM20948SourcePLLReady:
		reg, mask = reg20948IntEnable, 0x04
	case ICM20948SourceFifoOverflow:
		reg, mask = reg20948IntEnable2, 0x1F
	default:
		return fmt.Errorf("ICM20948: unknown interrupt source 0x%02X", source)
	}
	v, err := d.rd1(0, reg)
	if err != nil {
		return err
	}
	if on {
		v |= mask
	} else {
		v &^= mask
	}
	return d.wr1(0, reg, v)
}

// EnableInterrupt enables one interrupt source (an ICM20948Source* constant).
func (d *ICM20948Full) EnableInterrupt(source uint8) error { return d.intEnable(source, true) }

// DisableInterrupt disables one interrupt source.
func (d *ICM20948Full) DisableInterrupt(source uint8) error { return d.intEnable(source, false) }

// PollInterrupt reads (and clears) INT_STATUS, INT_STATUS_1 and INT_STATUS_2
// and returns the combined ICM20948Source* bits.
func (d *ICM20948Full) PollInterrupt() (uint8, error) {
	s, err := d.rd(0, reg20948IntStatus, 3)
	if err != nil {
		return 0, err
	}
	var status uint8
	if s[1]&0x01 != 0 {
		status |= ICM20948SourceDataReady
	}
	if s[0]&0x08 != 0 {
		status |= ICM20948SourceWakeOnMotion
	}
	if s[0]&0x01 != 0 {
		status |= ICM20948SourceI2CMaster
	}
	if s[0]&0x04 != 0 {
		status |= ICM20948SourcePLLReady
	}
	if s[2]&0x1F != 0 {
		status |= ICM20948SourceFifoOverflow
	}
	return status, nil
}

// OnInterrupt subscribes callback to the chip's INT1 line. The callback
// receives the PollInterrupt status and is only called for a non-zero status.
// The delivery uses the connection's IntPin when one is wired and falls back
// to a polling goroutine otherwise.
func (d *ICM20948Full) OnInterrupt(callback func(uint8)) error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	d.mu.Lock()
	d.callback = callback
	trigger := connection.Rising
	if d.activeLow {
		trigger = connection.Falling
	}
	d.mu.Unlock()

	var pin connection.InputPin = d.conn.IntPin()
	if d.conn.IntPin() == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		pin = poll
	}
	unsub := pin.OnEdge(trigger, func() { d.handleEdge() })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops the interrupt delivery.
func (d *ICM20948Full) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	d.callback = nil
	poll := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (d *ICM20948Full) handleEdge() {
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb == nil {
		return
	}
	if status, err := d.PollInterrupt(); err == nil && status != 0 {
		cb(status)
	}
}

func (d *ICM20948Full) averageRaw() (a, g [3]float32, err error) {
	const samples = 5
	var sa, sg [3]int32
	for n := 0; n < samples; n++ {
		ax, ay, az, err := d.AccelRaw()
		if err != nil {
			return a, g, err
		}
		gx, gy, gz, err := d.GyroRaw()
		if err != nil {
			return a, g, err
		}
		for i, v := range [3]int16{ax, ay, az} {
			sa[i] += int32(v)
		}
		for i, v := range [3]int16{gx, gy, gz} {
			sg[i] += int32(v)
		}
		time.Sleep(11 * time.Millisecond)
	}
	for i := 0; i < 3; i++ {
		a[i] = float32(sa[i]) / samples
		g[i] = float32(sg[i]) / samples
	}
	return a, g, nil
}

// SelfTest runs the self-test enable procedure and returns the raw response
// (output with self-test enabled minus output without) per axis. It
// configures ±2 g / ±250 dps, averages samples, enables the self-test bits,
// waits, averages again and restores the configuration. There is no
// pass/fail: the datasheet gives no limits.
func (d *ICM20948Full) SelfTest() (accel [3]float32, gyro [3]float32, err error) {
	savedA, savedG := d.accelFs, d.gyroFs
	if err = d.ConfigureAccel(0); err != nil {
		return
	}
	if err = d.ConfigureGyro(0); err != nil {
		return
	}
	time.Sleep(50 * time.Millisecond)
	a0, g0, err := d.averageRaw()
	if err != nil {
		return accel, gyro, err
	}
	if err = d.lpSuspend(); err != nil {
		return
	}
	if err = d.wr1(2, reg20948GyroConfig2, 0x38); err != nil {
		return
	}
	if err = d.wr1(2, reg20948AccelConfig2, 0x1C); err != nil {
		return
	}
	time.Sleep(50 * time.Millisecond)
	a1, g1, err := d.averageRaw()
	if err != nil {
		return accel, gyro, err
	}
	if err = d.wr1(2, reg20948GyroConfig2, 0x00); err != nil {
		return
	}
	if err = d.wr1(2, reg20948AccelConfig2, 0x00); err != nil {
		return
	}
	if err = d.lpResume(); err != nil {
		return
	}
	if err = d.ConfigureAccel(savedA); err != nil {
		return
	}
	if err = d.ConfigureGyro(savedG); err != nil {
		return
	}
	for i := 0; i < 3; i++ {
		accel[i] = a1[i] - a0[i]
		gyro[i] = g1[i] - g0[i]
	}
	return accel, gyro, nil
}

// ReadTimebaseCorrection reads the per-part PLL time-base correction in
// percent (0.079 %/LSB, signed).
func (d *ICM20948Full) ReadTimebaseCorrection() (float32, error) {
	v, err := d.rd1(1, reg20948TimebaseCorrection)
	if err != nil {
		return 0, err
	}
	return float32(int8(v)) * 0.079, nil
}

// Reset issues DEVICE_RESET and waits. The chip is left in sleep with default
// registers; construct a new driver afterwards to run the initialization again.
func (d *ICM20948Full) Reset() error {
	if err := d.wr1(0, reg20948PwrMgmt1, 0x80); err != nil {
		return err
	}
	time.Sleep(icm20948ResetDelay)
	d.bank = 0
	d.lpEn = false
	d.lpSuspended = false
	d.magEnabled = false
	return nil
}

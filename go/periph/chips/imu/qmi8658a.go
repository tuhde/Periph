package imu

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// QMI8658A register map.
const (
	regQmiWhoAmI       = 0x00
	regQmiRevisionID   = 0x01
	regQmiCtrl1        = 0x02
	regQmiCtrl2        = 0x03
	regQmiCtrl3        = 0x04
	regQmiCtrl5        = 0x06
	regQmiCtrl7        = 0x08
	regQmiCtrl8        = 0x09
	regQmiCtrl9        = 0x0A
	regQmiCal1L        = 0x0B
	regQmiFifoWtmTh    = 0x13
	regQmiFifoCtrl     = 0x14
	regQmiFifoSmplCnt  = 0x15
	regQmiFifoStatus   = 0x16
	regQmiFifoData     = 0x17
	regQmiStatusInt    = 0x2D
	regQmiStatus0      = 0x2E
	regQmiTimestamp    = 0x30
	regQmiTempL        = 0x33
	regQmiAxL          = 0x35
	regQmiGxL          = 0x3B
	regQmiCodStatus    = 0x46
	regQmiFwVersion    = 0x49
	regQmiResetStatus  = 0x4D
	regQmiDvxL         = 0x51
	regQmiTapStatus    = 0x59
	regQmiStepCnt      = 0x5A
	regQmiReset        = 0x60
	whoAmIQmi8658Value = 0x05
	qmiResetCmd        = 0xB0
)

// CTRL9 command codes.
const (
	qmiCmdAck             = 0x00
	qmiCmdRstFifo         = 0x04
	qmiCmdReqFifo         = 0x05
	qmiCmdWriteWom        = 0x08
	qmiCmdAccelOffset     = 0x09
	qmiCmdGyroOffset      = 0x0A
	qmiCmdConfigTap       = 0x0C
	qmiCmdConfigPedometer = 0x0D
	qmiCmdConfigMotion    = 0x0E
	qmiCmdResetPedometer  = 0x0F
	qmiCmdCopyUsid        = 0x10
	qmiCmdSetRpu          = 0x11
	qmiCmdAhbClockGating  = 0x12
	qmiCmdCod             = 0xA2
	qmiCmdApplyGyroGains  = 0xAA
)

// QMI8658A interrupt source bits, returned by PollInterrupt and accepted by
// EnableInterrupt / DisableInterrupt.
const (
	QMI8658ASourceDataReady         uint16 = 0x0001
	QMI8658ASourceFifoWatermark     uint16 = 0x0002
	QMI8658ASourceFifoFull          uint16 = 0x0004
	QMI8658ASourceAnyMotion         uint16 = 0x0008
	QMI8658ASourceNoMotion          uint16 = 0x0010
	QMI8658ASourceSignificantMotion uint16 = 0x0020
	QMI8658ASourceStep              uint16 = 0x0040
	QMI8658ASourceTap               uint16 = 0x0080
	QMI8658ASourceWakeOnMotion      uint16 = 0x0100
)

const (
	qmiG      = 9.80665
	qmiDegRad = math.Pi / 180.0
)

func qmiLE16(b []byte, i int) int16 { return int16(uint16(b[i]) | uint16(b[i+1])<<8) }

func qmiRound(v float64) int {
	if v >= 0 {
		return int(v + 0.5)
	}
	return int(v - 0.5)
}

func qmiClampU8(v int) byte {
	if v < 0 {
		return 0
	}
	if v > 255 {
		return 255
	}
	return byte(v)
}

func qmiEngineBit(source uint16) byte {
	switch source {
	case QMI8658ASourceAnyMotion:
		return 0x02
	case QMI8658ASourceNoMotion:
		return 0x04
	case QMI8658ASourceSignificantMotion:
		return 0x08
	case QMI8658ASourceStep:
		return 0x10
	case QMI8658ASourceTap:
		return 0x01
	}
	return 0
}

// QMI8658AMinimal is the QMI8658A 6-axis IMU driver (QST) — minimal
// interface: 3-axis acceleration and 3-axis angular rate.
//
// The constructor runs the datasheet initialisation sequence: soft reset,
// WHO_AM_I check, address auto-increment on, little-endian reads, CTRL9
// handshake by polling, both sensors on.
//
// Default configuration baked in:
//   - Accelerometer ±2 g, gyroscope ±256 dps
//   - Both output data rates code 5 (224.2 Hz in 6DOF mode)
//   - Both low-pass filters on, mode 3 (13.37 % of ODR)
//   - FIFO bypass (data-ready active), all INT pins high-Z, engines off
type QMI8658AMinimal struct {
	conn      connection.RegisterConnection
	ctrl1     byte
	ctrl7     byte
	ctrl8     byte
	accelFs   uint8
	gyroFs    uint8
	accelOdr  uint8
	gyroOdr   uint8
	fifoCtrl  byte
	fifoWtm   byte
	syncLock  bool
	accelSens float64
	gyroSens  float64
}

// NewQMI8658AMinimal resets the device, verifies WHO_AM_I and configures the
// defaults. conn must be a configured I²C connection (0x6B with SA0 = GND,
// 0x6A with SA0 = VDDIO) or an SPI connection built with read bit 0x80 and no
// multi-byte bit.
func NewQMI8658AMinimal(conn connection.RegisterConnection) (*QMI8658AMinimal, error) {
	d := &QMI8658AMinimal{conn: conn, ctrl1: 0x40, ctrl7: 0x03, ctrl8: 0x80, gyroFs: 4, accelOdr: 5, gyroOdr: 5}
	d.updateSens()
	time.Sleep(15 * time.Millisecond)
	if err := d.w(regQmiReset, qmiResetCmd); err != nil {
		return nil, err
	}
	time.Sleep(15 * time.Millisecond)
	if _, err := d.r1(regQmiResetStatus); err != nil { // 0x80 after reset, informational
		return nil, err
	}
	who, err := d.r1(regQmiWhoAmI)
	if err != nil {
		return nil, err
	}
	if who != whoAmIQmi8658Value {
		return nil, fmt.Errorf("QMI8658A WHO_AM_I: expected 0x%02X, got 0x%02X", whoAmIQmi8658Value, who)
	}
	for _, w := range [][2]byte{
		{regQmiCtrl1, d.ctrl1}, // ADDR_AI = 1, BE = 0
		{regQmiCtrl8, d.ctrl8}, // handshake by polling
		{regQmiCtrl2, 0x05},
		{regQmiCtrl3, 0x45},
		{regQmiCtrl5, 0x77},
		{regQmiCtrl7, d.ctrl7},
	} {
		if err := d.w(w[0], w[1]); err != nil {
			return nil, err
		}
	}
	time.Sleep(165 * time.Millisecond) // gyroscope turn-on
	return d, nil
}

func (d *QMI8658AMinimal) w(reg byte, v byte) error { return d.conn.WriteReg(uint32(reg), []byte{v}) }

func (d *QMI8658AMinimal) r1(reg byte) (byte, error) {
	b, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *QMI8658AMinimal) updateSens() {
	d.accelSens = float64(int(16384) >> d.accelFs)
	d.gyroSens = float64(int(2048) >> d.gyroFs)
}

func (d *QMI8658AMinimal) waitStatus(mask byte, want bool, timeout time.Duration, what string) error {
	deadline := time.Now().Add(timeout)
	for {
		st, err := d.r1(regQmiStatusInt)
		if err != nil {
			return err
		}
		if (st&mask != 0) == want {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("QMI8658A timeout waiting for %s", what)
		}
		time.Sleep(time.Millisecond)
	}
}

// readData reads sensor-data registers, waiting for the SyncSample lock when
// that mode is on.
func (d *QMI8658AMinimal) readData(reg byte, length int) ([]byte, error) {
	if d.syncLock {
		if err := d.waitStatus(0x01, true, 100*time.Millisecond, "SyncSample Avail"); err != nil {
			return nil, err
		}
		if err := d.waitStatus(0x02, true, 100*time.Millisecond, "SyncSample Locked"); err != nil {
			return nil, err
		}
	}
	return d.conn.ReadReg(uint32(reg), length)
}

func (d *QMI8658AMinimal) vec(reg byte, sens, scale float64) (float32, float32, float32, error) {
	b, err := d.readData(reg, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return float32(float64(qmiLE16(b, 0)) / sens * scale), float32(float64(qmiLE16(b, 2)) / sens * scale),
		float32(float64(qmiLE16(b, 4)) / sens * scale), nil
}

// Accel reads the 3-axis linear acceleration (x, y, z) in m/s².
func (d *QMI8658AMinimal) Accel() (float32, float32, float32, error) {
	return d.vec(regQmiAxL, d.accelSens, qmiG)
}

// Gyro reads the 3-axis angular rate (x, y, z) in rad/s.
func (d *QMI8658AMinimal) Gyro() (float32, float32, float32, error) {
	return d.vec(regQmiGxL, d.gyroSens, qmiDegRad)
}

// QMI8658AFull is the full QMI8658A driver; it embeds QMI8658AMinimal and
// adds raw and burst reads, temperature, sample counter, full-scale / ODR /
// filter configuration, power control, SyncSample data locking, the FIFO, the
// motion engines (any / no / significant motion, tap, pedometer,
// wake-on-motion), offsets, calibration-on-demand, self-test, device info,
// pull-up control and interrupts.
type QMI8658AFull struct {
	*QMI8658AMinimal

	anyThr, noThr             float64
	anyWin, noWin             byte
	anyAxes, noAxes           byte
	anyAnd, noAnd             bool
	sigWait, sigConfirm       uint16
	intSources                uint16
	fifoPin, activityPin, wom uint8

	mu          sync.Mutex
	callback    func(uint16)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewQMI8658AFull creates the Full driver; conn is as for NewQMI8658AMinimal.
func NewQMI8658AFull(conn connection.RegisterConnection) (*QMI8658AFull, error) {
	m, err := NewQMI8658AMinimal(conn)
	if err != nil {
		return nil, err
	}
	return &QMI8658AFull{
		QMI8658AMinimal: m,
		anyThr:          0.25, anyWin: 3, anyAxes: 0x07,
		noThr: 0.125, noWin: 30, noAxes: 0x07, noAnd: true,
		sigWait: 150, sigConfirm: 600,
		fifoPin: 2, activityPin: 2, wom: 1,
	}, nil
}

func (d *QMI8658AFull) setCtrl1(v byte) error { d.ctrl1 = v; return d.w(regQmiCtrl1, v) }
func (d *QMI8658AFull) setCtrl7(v byte) error { d.ctrl7 = v; return d.w(regQmiCtrl7, v) }
func (d *QMI8658AFull) setCtrl8(v byte) error { d.ctrl8 = v; return d.w(regQmiCtrl8, v) }

func (d *QMI8658AFull) ctrl9(cmd byte, timeout time.Duration) error {
	if err := d.w(regQmiCtrl9, cmd); err != nil {
		return err
	}
	doneErr := d.waitStatus(0x80, true, timeout, "CTRL9 CmdDone")
	if err := d.w(regQmiCtrl9, qmiCmdAck); err != nil {
		return err
	}
	if doneErr != nil {
		return doneErr
	}
	return d.waitStatus(0x80, false, timeout, "CTRL9 ack")
}

func (d *QMI8658AFull) calCmd(cmd byte, params []byte, timeout time.Duration) error {
	for i := 0; i < 8; i++ {
		var v byte
		if i < len(params) {
			v = params[i]
		}
		if err := d.w(byte(regQmiCal1L+i), v); err != nil {
			return err
		}
	}
	return d.ctrl9(cmd, timeout)
}

func (d *QMI8658AFull) twoSets(cmd byte, set1, set2 []byte) error {
	p := make([]byte, 8)
	copy(p, set1)
	p[7] = 0x10
	if err := d.calCmd(cmd, p, 100*time.Millisecond); err != nil {
		return err
	}
	p = make([]byte, 8)
	copy(p, set2)
	p[7] = 0x20
	return d.calCmd(cmd, p, 100*time.Millisecond)
}

func (d *QMI8658AFull) sensorsOff() (byte, error) {
	prev := d.ctrl7 & 0x03
	if prev != 0 {
		if err := d.setCtrl7(d.ctrl7 &^ 0x03); err != nil {
			return 0, err
		}
		time.Sleep(5 * time.Millisecond)
	}
	return prev, nil
}

func (d *QMI8658AFull) sensorsRestore(prev byte) error {
	if prev != 0 {
		if err := d.setCtrl7(d.ctrl7 | prev); err != nil {
			return err
		}
		if prev&0x02 != 0 {
			time.Sleep(165 * time.Millisecond)
		} else {
			time.Sleep(10 * time.Millisecond)
		}
	}
	return nil
}

// withSensorsOff runs fn with both sensors disabled and restores them.
func (d *QMI8658AFull) withSensorsOff(fn func() error) error {
	prev, err := d.sensorsOff()
	if err != nil {
		return err
	}
	fnErr := fn()
	if err := d.sensorsRestore(prev); err != nil && fnErr == nil {
		fnErr = err
	}
	return fnErr
}

// AccelRaw reads the raw signed 16-bit accelerometer counts.
func (d *QMI8658AFull) AccelRaw() (int16, int16, int16, error) {
	b, err := d.readData(regQmiAxL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return qmiLE16(b, 0), qmiLE16(b, 2), qmiLE16(b, 4), nil
}

// GyroRaw reads the raw signed 16-bit gyroscope counts.
func (d *QMI8658AFull) GyroRaw() (int16, int16, int16, error) {
	b, err := d.readData(regQmiGxL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return qmiLE16(b, 0), qmiLE16(b, 2), qmiLE16(b, 4), nil
}

// ReadAll burst-reads temperature, acceleration and angular rate so all three
// belong to one sample: accel in m/s², gyro in rad/s, tempC in °C.
func (d *QMI8658AFull) ReadAll() (accel [3]float32, gyro [3]float32, tempC float32, err error) {
	b, err := d.readData(regQmiTempL, 14)
	if err != nil {
		return
	}
	tempC = float32(float64(qmiLE16(b, 0)) / 256.0)
	for i := 0; i < 3; i++ {
		accel[i] = float32(float64(qmiLE16(b, 2+2*i)) / d.accelSens * qmiG)
		gyro[i] = float32(float64(qmiLE16(b, 8+2*i)) / d.gyroSens * qmiDegRad)
	}
	return
}

// Temperature reads the die temperature in °C.
func (d *QMI8658AFull) Temperature() (float32, error) {
	b, err := d.readData(regQmiTempL, 2)
	if err != nil {
		return 0, err
	}
	return float32(float64(qmiLE16(b, 0)) / 256.0), nil
}

// Timestamp reads the 24-bit sample counter (wraps at 0xFFFFFF).
func (d *QMI8658AFull) Timestamp() (uint32, error) {
	b, err := d.conn.ReadReg(regQmiTimestamp, 3)
	if err != nil {
		return 0, err
	}
	return uint32(b[0]) | uint32(b[1])<<8 | uint32(b[2])<<16, nil
}

// DataReady reports whether an enabled sensor has new data (STATUSINT.Avail
// while SyncSample is on).
func (d *QMI8658AFull) DataReady() (bool, error) {
	if d.syncLock {
		v, err := d.r1(regQmiStatusInt)
		return v&0x01 != 0, err
	}
	v, err := d.r1(regQmiStatus0)
	return v&0x03 != 0, err
}

// ConfigureAccel configures the accelerometer (CTRL2): fullScale 0 = ±2 g,
// 1 = ±4 g, 2 = ±8 g, 3 = ±16 g; odr is the ODR code 0–8 or 12–15 (12–15 only
// with the gyroscope off).
func (d *QMI8658AFull) ConfigureAccel(fullScale, odr uint8) error {
	if fullScale > 3 || !(odr <= 8 || (odr >= 12 && odr <= 15)) {
		return errors.New("QMI8658A: accel full scale must be 0-3 and odr 0-8 or 12-15")
	}
	d.accelFs, d.accelOdr = fullScale, odr
	d.updateSens()
	return d.w(regQmiCtrl2, fullScale<<4|odr)
}

// ConfigureGyro configures the gyroscope (CTRL3): fullScale 0 = ±16 dps …
// 7 = ±2048 dps (doubling); odr is the ODR code 0–8.
func (d *QMI8658AFull) ConfigureGyro(fullScale, odr uint8) error {
	if fullScale > 7 || odr > 8 {
		return errors.New("QMI8658A: gyro full scale must be 0-7 and odr 0-8")
	}
	d.gyroFs, d.gyroOdr = fullScale, odr
	d.updateSens()
	return d.w(regQmiCtrl3, fullScale<<4|odr)
}

func b2u(b bool) byte {
	if b {
		return 1
	}
	return 0
}

// ConfigureFilters configures the low-pass filters (CTRL5); modes 0–3 =
// 2.66 / 3.63 / 5.39 / 13.37 % of ODR.
func (d *QMI8658AFull) ConfigureFilters(accelFilter bool, accelMode uint8, gyroFilter bool, gyroMode uint8) error {
	if accelMode > 3 || gyroMode > 3 {
		return errors.New("QMI8658A: filter mode must be 0-3")
	}
	return d.w(regQmiCtrl5, gyroMode<<5|b2u(gyroFilter)<<4|accelMode<<1|b2u(accelFilter))
}

// SetSensors enables or disables the sensors (CTRL7 aEN / gEN / gSN). The
// gyroscope needs 150 ms + 3/ODR, the accelerometer 3 ms + 3/ODR to become
// valid.
func (d *QMI8658AFull) SetSensors(accel, gyro, gyroSnooze bool) error {
	return d.setCtrl7(d.ctrl7&^0x13 | b2u(accel) | b2u(gyro)<<1 | b2u(gyroSnooze)<<4)
}

// SetPowerDown powers the chip down (CTRL1.SensorDisable, sensors disabled
// first) or wakes it.
func (d *QMI8658AFull) SetPowerDown(down bool) error {
	if down {
		if err := d.setCtrl7(d.ctrl7 &^ 0x03); err != nil {
			return err
		}
		return d.setCtrl1(d.ctrl1 | 0x01)
	}
	if err := d.setCtrl1(d.ctrl1 &^ 0x01); err != nil {
		return err
	}
	time.Sleep(15 * time.Millisecond)
	return nil
}

// SetSyncSample enables or disables SyncSample (data-locking) mode;
// incompatible with the FIFO and the motion engines.
func (d *QMI8658AFull) SetSyncSample(enabled bool) error {
	if enabled {
		if err := d.calCmd(qmiCmdAhbClockGating, []byte{1}, 100*time.Millisecond); err != nil {
			return err
		}
		if err := d.setCtrl7(d.ctrl7 | 0x80); err != nil {
			return err
		}
	} else {
		if err := d.setCtrl7(d.ctrl7 &^ 0x80); err != nil {
			return err
		}
		if err := d.calCmd(qmiCmdAhbClockGating, []byte{0}, 100*time.Millisecond); err != nil {
			return err
		}
	}
	d.syncLock = enabled
	return nil
}

// ConfigureFifo configures the FIFO: mode 0 = bypass, 1 = FIFO (stops when
// full), 2 = stream; size 0–3 = 16 / 32 / 64 / 128 samples; watermark in
// samples (0 disables).
func (d *QMI8658AFull) ConfigureFifo(mode, size, watermark uint8) error {
	if mode > 2 || size > 3 {
		return errors.New("QMI8658A: FIFO mode must be 0-2 and size 0-3")
	}
	d.fifoWtm = watermark
	if err := d.w(regQmiFifoWtmTh, watermark); err != nil {
		return err
	}
	d.fifoCtrl = size<<2 | mode
	if err := d.w(regQmiFifoCtrl, d.fifoCtrl); err != nil {
		return err
	}
	if mode == 0 {
		return d.setCtrl7(d.ctrl7 &^ 0x20) // DRDY back on
	}
	return nil
}

// FifoStatus returns the raw FIFO_STATUS byte (full 7, watermark 6, overflow
// 5, not-empty 4).
func (d *QMI8658AFull) FifoStatus() (byte, error) { return d.r1(regQmiFifoStatus) }

// FifoCount returns the number of bytes in the FIFO (2 × the sample-word count).
func (d *QMI8658AFull) FifoCount() (int, error) {
	st, err := d.r1(regQmiFifoStatus)
	if err != nil {
		return 0, err
	}
	lo, err := d.r1(regQmiFifoSmplCnt)
	if err != nil {
		return 0, err
	}
	return 2 * (int(st&0x03)<<8 | int(lo)), nil
}

// ReadFifo drains up to count bytes (all available when count < 0) with the
// read-mode procedure. Frames are little-endian 6-byte vectors, accelerometer
// first when both sensors are on (12 bytes per sample).
func (d *QMI8658AFull) ReadFifo(count int) ([]byte, error) {
	total, err := d.FifoCount()
	if err != nil {
		return nil, err
	}
	n := total
	if count >= 0 && count < total {
		n = count
	}
	if n == 0 {
		return []byte{}, nil
	}
	if err := d.ctrl9(qmiCmdReqFifo, 100*time.Millisecond); err != nil {
		return nil, err
	}
	if err := d.w(regQmiCtrl1, d.ctrl1&^0x40); err != nil { // ADDR_AI off: FIFO_DATA repeats
		return nil, err
	}
	out := make([]byte, 0, n)
	var readErr error
	for len(out) < n {
		chunk := n - len(out)
		if chunk > 32 {
			chunk = 32
		}
		b, err := d.conn.ReadReg(regQmiFifoData, chunk)
		if err != nil {
			readErr = err
			break
		}
		out = append(out, b...)
	}
	if err := d.w(regQmiCtrl1, d.ctrl1); err != nil {
		return nil, err
	}
	if err := d.w(regQmiFifoCtrl, d.fifoCtrl&0x7F); err != nil {
		return nil, err
	}
	if readErr != nil {
		return nil, readErr
	}
	return out, nil
}

// ResetFifo resets the FIFO (CTRL9 RST_FIFO).
func (d *QMI8658AFull) ResetFifo() error { return d.ctrl9(qmiCmdRstFifo, 100*time.Millisecond) }

func (d *QMI8658AFull) sendMotion() error {
	return d.withSensorsOff(func() error {
		a := qmiClampU8(qmiRound(d.anyThr * 32))
		n := qmiClampU8(qmiRound(d.noThr * 32))
		mode := b2u(d.noAnd)<<7 | (d.noAxes&7)<<4 | b2u(d.anyAnd)<<3 | (d.anyAxes & 7)
		return d.twoSets(qmiCmdConfigMotion,
			[]byte{a, a, a, n, n, n, mode},
			[]byte{d.anyWin, d.noWin, byte(d.sigWait), byte(d.sigWait >> 8), byte(d.sigConfirm), byte(d.sigConfirm >> 8)})
	})
}

// ConfigureAnyMotion configures the any-motion engine: slope thresholdG in g
// (1/32 g steps, 0–7.97), window in samples, axes mask (bit 0 X, 1 Y, 2 Z),
// logicAnd = AND instead of OR over the enabled axes.
func (d *QMI8658AFull) ConfigureAnyMotion(thresholdG float64, window, axes uint8, logicAnd bool) error {
	d.anyThr, d.anyWin, d.anyAxes, d.anyAnd = thresholdG, window, axes, logicAnd
	return d.sendMotion()
}

// ConfigureNoMotion configures the no-motion engine (arguments as for
// ConfigureAnyMotion).
func (d *QMI8658AFull) ConfigureNoMotion(thresholdG float64, window, axes uint8, logicAnd bool) error {
	d.noThr, d.noWin, d.noAxes, d.noAnd = thresholdG, window, axes, logicAnd
	return d.sendMotion()
}

// ConfigureSignificantMotion configures the significant-motion engine (16-bit
// windows in samples); any- and no-motion must be configured and enabled.
func (d *QMI8658AFull) ConfigureSignificantMotion(waitWindow, confirmWindow uint16) error {
	d.sigWait, d.sigConfirm = waitWindow, confirmWindow
	return d.sendMotion()
}

// EnableMotion enables the motion engines (CTRL8 bits 1–3).
func (d *QMI8658AFull) EnableMotion(anyMotion, noMotion, significant bool) error {
	return d.setCtrl8(d.ctrl8&^0x0E | b2u(anyMotion)<<1 | b2u(noMotion)<<2 | b2u(significant)<<3)
}

// ConfigureTap configures the tap engine (two CTRL9 CONFIGURE_TAP commands):
// priority 0–5 axis order; windows in samples; alpha / gamma in 1/128 steps;
// thresholds in g² (1/1024 steps).
func (d *QMI8658AFull) ConfigureTap(priority, peakWindow uint8, tapWindow, doubleTapWindow uint16,
	alpha, gamma, peakThreshold, quietThreshold float64) error {
	peak := uint16(qmiRound(peakThreshold * 1024))
	quiet := uint16(qmiRound(quietThreshold * 1024))
	return d.withSensorsOff(func() error {
		return d.twoSets(qmiCmdConfigTap,
			[]byte{peakWindow, priority, byte(tapWindow), byte(tapWindow >> 8), byte(doubleTapWindow), byte(doubleTapWindow >> 8)},
			[]byte{byte(qmiRound(alpha*128)) & 0x7F, byte(qmiRound(gamma*128)) & 0x7F, byte(peak), byte(peak >> 8), byte(quiet), byte(quiet >> 8)})
	})
}

// EnableTap enables or disables the tap engine (CTRL8.Tap_EN). Use an
// accelerometer ODR above 200 Hz.
func (d *QMI8658AFull) EnableTap(enabled bool) error {
	if enabled {
		return d.setCtrl8(d.ctrl8 | 0x01)
	}
	return d.setCtrl8(d.ctrl8 &^ 0x01)
}

// TapStatus reads the last tap event: count 0/1/2, axis 0 none / 1 X / 2 Y /
// 3 Z, negative polarity.
func (d *QMI8658AFull) TapStatus() (count, axis uint8, negative bool, err error) {
	v, err := d.r1(regQmiTapStatus)
	if err != nil {
		return 0, 0, false, err
	}
	return v & 0x03, (v >> 4) & 0x03, v&0x80 != 0, nil
}

// ConfigurePedometer configures the pedometer (two CTRL9 CONFIGURE_PEDOMETER
// commands; the documented defaults are for a 50 Hz ODR: 50, 200, 100, 200,
// 20, 10, 0, 4).
func (d *QMI8658AFull) ConfigurePedometer(sampleCount uint16, peakToPeakMg, peakMg float64, timeUp uint16,
	timeLow, entryCount, precision, reportEvery uint8) error {
	p2p := uint16(qmiRound(peakToPeakMg / 1000.0 * 1024))
	pk := uint16(qmiRound(peakMg / 1000.0 * 1024))
	return d.withSensorsOff(func() error {
		return d.twoSets(qmiCmdConfigPedometer,
			[]byte{byte(sampleCount), byte(sampleCount >> 8), byte(p2p), byte(p2p >> 8), byte(pk), byte(pk >> 8)},
			[]byte{byte(timeUp), byte(timeUp >> 8), timeLow, entryCount, precision, reportEvery})
	})
}

// EnablePedometer enables or disables the pedometer (CTRL8.Pedo_EN); a 0→1
// transition resets the count.
func (d *QMI8658AFull) EnablePedometer(enabled bool) error {
	if enabled {
		return d.setCtrl8(d.ctrl8 | 0x10)
	}
	return d.setCtrl8(d.ctrl8 &^ 0x10)
}

// StepCount reads the 24-bit step count.
func (d *QMI8658AFull) StepCount() (uint32, error) {
	b, err := d.conn.ReadReg(regQmiStepCnt, 3)
	if err != nil {
		return 0, err
	}
	return uint32(b[0]) | uint32(b[1])<<8 | uint32(b[2])<<16, nil
}

// ResetStepCount clears the step count (CTRL9 RESET_PEDOMETER).
func (d *QMI8658AFull) ResetStepCount() error {
	return d.ctrl9(qmiCmdResetPedometer, 100*time.Millisecond)
}

// SetWakeOnMotion enters (thresholdMg 1–255) or leaves (0) wake-on-motion
// mode using accelerometer ±2 g at ODR code 12 (128 Hz). interruptPin is 1 or
// 2, initialLevel the pin level after enabling, blankingSamples 0–63.
func (d *QMI8658AFull) SetWakeOnMotion(thresholdMg, interruptPin, initialLevel, blankingSamples uint8) error {
	if _, err := d.sensorsOff(); err != nil {
		return err
	}
	if thresholdMg == 0 {
		if err := d.calCmd(qmiCmdWriteWom, []byte{0, 0}, 100*time.Millisecond); err != nil {
			return err
		}
		d.intSources &^= QMI8658ASourceWakeOnMotion
		return d.updateIntPins()
	}
	d.accelFs, d.accelOdr = 0, 12
	d.updateSens()
	if err := d.w(regQmiCtrl2, 0x0C); err != nil {
		return err
	}
	d.wom = 1
	if interruptPin == 2 {
		d.wom = 2
	}
	sel := byte(0)
	if d.wom == 2 {
		sel |= 0x40
	}
	if initialLevel != 0 {
		sel |= 0x80
	}
	if err := d.calCmd(qmiCmdWriteWom, []byte{thresholdMg, sel | blankingSamples&0x3F}, 100*time.Millisecond); err != nil {
		return err
	}
	d.intSources |= QMI8658ASourceWakeOnMotion
	if err := d.updateIntPins(); err != nil {
		return err
	}
	if err := d.setCtrl7(d.ctrl7&^0x03 | 0x01); err != nil {
		return err
	}
	time.Sleep(10 * time.Millisecond)
	return nil
}

func (d *QMI8658AFull) offsetCmd(cmd byte, codes [3]int16) error {
	return d.withSensorsOff(func() error {
		p := make([]byte, 6)
		for i, c := range codes {
			p[2*i] = byte(uint16(c))
			p[2*i+1] = byte(uint16(c) >> 8)
		}
		return d.calCmd(cmd, p, 100*time.Millisecond)
	})
}

// SetAccelOffset applies an accelerometer offset in m/s² (signed 4.12 g;
// volatile — lost on reset or power cycle).
func (d *QMI8658AFull) SetAccelOffset(x, y, z float64) error {
	c := func(v float64) int16 { return int16(qmiRound(v / qmiG * 4096)) }
	return d.offsetCmd(qmiCmdAccelOffset, [3]int16{c(x), c(y), c(z)})
}

// SetGyroOffset applies a gyroscope offset in rad/s (signed 11.5 dps; volatile).
func (d *QMI8658AFull) SetGyroOffset(x, y, z float64) error {
	c := func(v float64) int16 { return int16(qmiRound(v / qmiDegRad * 32)) }
	return d.offsetCmd(qmiCmdGyroOffset, [3]int16{c(x), c(y), c(z)})
}

// CalibrateGyro runs gyroscope calibration-on-demand (about 1.5 s; keep the
// device still). It returns success and the COD_STATUS register.
func (d *QMI8658AFull) CalibrateGyro() (bool, byte, error) {
	var status byte
	err := d.withSensorsOff(func() error {
		if err := d.ctrl9(qmiCmdCod, 3*time.Second); err != nil {
			return err
		}
		var rerr error
		status, rerr = d.r1(regQmiCodStatus)
		return rerr
	})
	if err != nil {
		return false, 0, err
	}
	return status == 0, status, nil
}

// GetGyroGains reads the raw X/Y/Z gyroscope gains, valid right after
// CalibrateGyro.
func (d *QMI8658AFull) GetGyroGains() (x, y, z uint16, err error) {
	b, err := d.conn.ReadReg(regQmiDvxL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return uint16(b[0]) | uint16(b[1])<<8, uint16(b[2]) | uint16(b[3])<<8, uint16(b[4]) | uint16(b[5])<<8, nil
}

// SetGyroGains restores previously saved gyroscope gains.
func (d *QMI8658AFull) SetGyroGains(x, y, z uint16) error {
	return d.withSensorsOff(func() error {
		return d.calCmd(qmiCmdApplyGyroGains,
			[]byte{byte(x), byte(x >> 8), byte(y), byte(y >> 8), byte(z), byte(z >> 8)}, 100*time.Millisecond)
	})
}

func (d *QMI8658AFull) selfTest(ctrlReg byte, saved byte, scale float64) (out [3]float64, err error) {
	err = d.withSensorsOff(func() error {
		if err := d.w(ctrlReg, saved|0x80); err != nil {
			return err
		}
		if err := d.waitStatus(0x01, true, 3*time.Second, "self-test Avail"); err != nil {
			return err
		}
		b, err := d.conn.ReadReg(regQmiDvxL, 6)
		if err != nil {
			return err
		}
		for i := 0; i < 3; i++ {
			out[i] = float64(qmiLE16(b, 2*i)) / scale
		}
		if err := d.w(ctrlReg, saved); err != nil {
			return err
		}
		return d.waitStatus(0x01, false, 3*time.Second, "self-test end")
	})
	return
}

// SelfTestAccel runs the accelerometer self-test (sensors at rest). It
// returns whether every axis exceeded 200 mg and the response in m/s².
func (d *QMI8658AFull) SelfTestAccel() (bool, [3]float32, error) {
	g, err := d.selfTest(regQmiCtrl2, d.accelFs<<4|d.accelOdr, 2048)
	var out [3]float32
	pass := err == nil
	for i := range g {
		out[i] = float32(g[i] * qmiG)
		if math.Abs(g[i]) <= 0.2 {
			pass = false
		}
	}
	return pass, out, err
}

// SelfTestGyro runs the gyroscope self-test. It returns whether every axis
// exceeded 300 dps and the response in rad/s.
func (d *QMI8658AFull) SelfTestGyro() (bool, [3]float32, error) {
	g, err := d.selfTest(regQmiCtrl3, d.gyroFs<<4|d.gyroOdr, 16)
	var out [3]float32
	pass := err == nil
	for i := range g {
		out[i] = float32(g[i] * qmiDegRad)
		if math.Abs(g[i]) <= 300 {
			pass = false
		}
	}
	return pass, out, err
}

// DeviceInfo reads the revision, the firmware version (3 bytes) and the USID
// (6 bytes) via CTRL9 COPY_USID.
func (d *QMI8658AFull) DeviceInfo() (revision byte, firmware [3]byte, usid [6]byte, err error) {
	if err = d.ctrl9(qmiCmdCopyUsid, 100*time.Millisecond); err != nil {
		return
	}
	if revision, err = d.r1(regQmiRevisionID); err != nil {
		return
	}
	b, err := d.conn.ReadReg(regQmiFwVersion, 2)
	if err != nil {
		return
	}
	copy(firmware[:2], b)
	if firmware[2], err = d.r1(regQmiFwVersion + 2); err != nil {
		return
	}
	u, err := d.conn.ReadReg(regQmiDvxL, 6)
	if err != nil {
		return
	}
	copy(usid[:], u)
	return
}

// SetPullups controls the internal 200 kΩ pull-ups (CTRL9 SET_RPU); false
// disables a group. Disabling i2c on a bus without external pull-ups breaks
// the bus.
func (d *QMI8658AFull) SetPullups(aux, sdx, cs, i2c bool) error {
	v := b2u(!aux) | b2u(!sdx)<<1 | b2u(!cs)<<2 | b2u(!i2c)<<3
	return d.calCmd(qmiCmdSetRpu, []byte{v}, 100*time.Millisecond)
}

// Reset performs a soft reset and waits 15 ms; the sensors are off afterwards.
func (d *QMI8658AFull) Reset() error {
	if err := d.w(regQmiReset, qmiResetCmd); err != nil {
		return err
	}
	time.Sleep(15 * time.Millisecond)
	d.ctrl1, d.ctrl7, d.ctrl8 = 0x40, 0x00, 0x80
	if err := d.w(regQmiCtrl1, d.ctrl1); err != nil {
		return err
	}
	if err := d.w(regQmiCtrl8, d.ctrl8); err != nil {
		return err
	}
	d.syncLock = false
	d.intSources = 0
	return nil
}

func (d *QMI8658AFull) sourcePin(source uint16) uint8 {
	switch source {
	case QMI8658ASourceDataReady:
		return 2
	case QMI8658ASourceFifoWatermark, QMI8658ASourceFifoFull:
		return d.fifoPin
	case QMI8658ASourceWakeOnMotion:
		return d.wom
	}
	return d.activityPin
}

func (d *QMI8658AFull) updateIntPins() error {
	var pins byte
	for bit := 0; bit < 9; bit++ {
		s := uint16(1) << bit
		if d.intSources&s != 0 {
			pins |= 1 << (d.sourcePin(s) - 1)
		}
	}
	v := d.ctrl1 &^ 0x18
	if pins&1 != 0 {
		v |= 0x08
	}
	if pins&2 != 0 {
		v |= 0x10
	}
	if d.fifoPin == 1 {
		v |= 0x04
	} else {
		v &^= 0x04
	}
	if v != d.ctrl1 {
		return d.setCtrl1(v)
	}
	return nil
}

// SetInterruptPins routes the FIFO interrupt (CTRL1.FIFO_INT_SEL) and the
// motion-engine group (CTRL8.ACTIVITY_INT_SEL) to INT1 (1) or INT2 (2).
func (d *QMI8658AFull) SetInterruptPins(fifoPin, activityPin uint8) error {
	d.fifoPin, d.activityPin = 2, 2
	if fifoPin == 1 {
		d.fifoPin = 1
	}
	if activityPin == 1 {
		d.activityPin = 1
	}
	v := d.ctrl8 &^ 0x40
	if d.activityPin == 1 {
		v |= 0x40
	}
	if err := d.setCtrl8(v); err != nil {
		return err
	}
	return d.updateIntPins()
}

// EnableInterrupt enables one interrupt source (a QMI8658ASource* constant).
func (d *QMI8658AFull) EnableInterrupt(source uint16) error {
	var err error
	switch {
	case source == QMI8658ASourceDataReady:
		err = d.setCtrl7(d.ctrl7 &^ 0x20)
	case source == QMI8658ASourceFifoWatermark:
		err = d.w(regQmiFifoWtmTh, d.fifoWtm)
	case qmiEngineBit(source) != 0:
		err = d.setCtrl8(d.ctrl8 | qmiEngineBit(source))
	}
	if err != nil {
		return err
	}
	d.intSources |= source
	return d.updateIntPins()
}

// DisableInterrupt disables one interrupt source (a QMI8658ASource* constant).
func (d *QMI8658AFull) DisableInterrupt(source uint16) error {
	var err error
	switch {
	case source == QMI8658ASourceDataReady:
		err = d.setCtrl7(d.ctrl7 | 0x20)
	case source == QMI8658ASourceFifoWatermark:
		err = d.w(regQmiFifoWtmTh, 0)
	case qmiEngineBit(source) != 0:
		err = d.setCtrl8(d.ctrl8 &^ qmiEngineBit(source))
	}
	if err != nil {
		return err
	}
	d.intSources &^= source
	return d.updateIntPins()
}

// PollInterrupt reads STATUS0, STATUS1 and FIFO_STATUS (clearing what clears
// on read) and returns the combined QMI8658ASource* bits.
func (d *QMI8658AFull) PollInterrupt() (uint16, error) {
	s, err := d.conn.ReadReg(regQmiStatus0, 2)
	if err != nil {
		return 0, err
	}
	fs, err := d.r1(regQmiFifoStatus)
	if err != nil {
		return 0, err
	}
	var st uint16
	if s[0]&0x03 != 0 {
		st |= QMI8658ASourceDataReady
	}
	if fs&0x40 != 0 {
		st |= QMI8658ASourceFifoWatermark
	}
	if fs&0x80 != 0 {
		st |= QMI8658ASourceFifoFull
	}
	if s[1]&0x20 != 0 {
		st |= QMI8658ASourceAnyMotion
	}
	if s[1]&0x40 != 0 {
		st |= QMI8658ASourceNoMotion
	}
	if s[1]&0x80 != 0 {
		st |= QMI8658ASourceSignificantMotion
	}
	if s[1]&0x10 != 0 {
		st |= QMI8658ASourceStep
	}
	if s[1]&0x02 != 0 {
		st |= QMI8658ASourceTap
	}
	if s[1]&0x04 != 0 {
		st |= QMI8658ASourceWakeOnMotion
	}
	return st, nil
}

// OnInterrupt subscribes callback to rising edges of the interrupt pin. The
// callback receives the PollInterrupt status and is only called for a
// non-zero status. The delivery uses the connection's IntPin when one is wired
// and falls back to a polling goroutine otherwise.
func (d *QMI8658AFull) OnInterrupt(callback func(uint16)) error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()

	var pin connection.InputPin = d.conn.IntPin()
	if d.conn.IntPin() == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		pin = poll
	}
	unsub := pin.OnEdge(connection.Rising, func() { d.handleEdge() })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops the interrupt delivery.
func (d *QMI8658AFull) OffInterrupt() error {
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

func (d *QMI8658AFull) handleEdge() {
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

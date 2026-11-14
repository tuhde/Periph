package imu

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BNO055 register map (page 0 unless noted).
const (
	regBnoChipID           = 0x00
	regBnoPageID           = 0x07
	regBnoAccData          = 0x08
	regBnoMagData          = 0x0E
	regBnoGyrData          = 0x14
	regBnoEulData          = 0x1A
	regBnoQuaData          = 0x20
	regBnoLiaData          = 0x28
	regBnoGrvData          = 0x2E
	regBnoTemp             = 0x34
	regBnoCalibStat        = 0x35
	regBnoStResult         = 0x36
	regBnoIntSta           = 0x37
	regBnoSysStatus        = 0x39
	regBnoUnitSel          = 0x3B
	regBnoOprMode          = 0x3D
	regBnoPwrMode          = 0x3E
	regBnoSysTrigger       = 0x3F
	regBnoTempSource       = 0x40
	regBnoAxisMapCfg       = 0x41
	regBnoAxisMapSign      = 0x42
	regBnoAccOffset        = 0x55
	regBnoIntMsk           = 0x0F // page 1
	regBnoIntEn            = 0x10 // page 1
	regBnoUniqueID         = 0x50 // page 1
	bnoChipIDValue         = 0xA0
	bnoUnitSelSI           = 0x86
	bnoModeConfig     byte = 0x00
	bnoModeNDOF       byte = 0x0C
)

// BNO055 operation modes for SetMode.
const (
	BNO055ModeConfig     byte = 0x0
	BNO055ModeAccOnly    byte = 0x1
	BNO055ModeMagOnly    byte = 0x2
	BNO055ModeGyroOnly   byte = 0x3
	BNO055ModeAccMag     byte = 0x4
	BNO055ModeAccGyro    byte = 0x5
	BNO055ModeMagGyro    byte = 0x6
	BNO055ModeAMG        byte = 0x7
	BNO055ModeIMU        byte = 0x8
	BNO055ModeCompass    byte = 0x9
	BNO055ModeM4G        byte = 0xA
	BNO055ModeNDOFFmcOff byte = 0xB
	BNO055ModeNDOF       byte = 0xC
)

// BNO055 interrupt source bits (INT_STA / INT_EN / INT_MSK layout).
const (
	BNO055SourceAccBsxDrdy  byte = 0x01
	BNO055SourceMagDrdy     byte = 0x02
	BNO055SourceGyroAM      byte = 0x04
	BNO055SourceGyrHighRate byte = 0x08
	BNO055SourceGyrDrdy     byte = 0x10
	BNO055SourceAccHighG    byte = 0x20
	BNO055SourceAccAM       byte = 0x40
	BNO055SourceAccNM       byte = 0x80
)

// BNO055 power modes for SetPowerMode.
const (
	BNO055PowerNormal   byte = 0
	BNO055PowerLowPower byte = 1
	BNO055PowerSuspend  byte = 2
)

// BNO055 temperature sources for SetTemperatureSource.
const (
	BNO055TempSourceAccelerometer byte = 0
	BNO055TempSourceGyroscope     byte = 1
)

// ErrBNO055ChipID is returned when CHIP_ID does not read 0xA0 within 650 ms.
var ErrBNO055ChipID = errors.New("BNO055 CHIP_ID: expected 0xA0")

// BNO055SelfTestResult is the result of SelfTest; true means passed.
type BNO055SelfTestResult struct {
	Acc, Mag, Gyr, MCU bool
}

// BNO055Revisions holds the identification registers 0x00–0x06.
type BNO055Revisions struct {
	ChipID, AccID, MagID, GyrID uint8
	SwRevision                  uint16
	Bootloader                  uint8
}

// BNO055Minimal is the BNO055 9-axis absolute-orientation driver (Bosch
// Sensortec) — minimal interface: fused Euler angles.
//
// The constructor waits for the chip to boot (up to 650 ms), checks CHIP_ID
// (0xA0), selects normal power mode and UNIT_SEL = 0x86 (Android orientation,
// Euler angles in radians, angular rate in rad/s, acceleration in m/s²,
// temperature in °C), then enters NDOF fusion mode (100 Hz absolute
// orientation, internal oscillator). The chip stretches the I²C clock, so the
// host bus must support clock stretching.
type BNO055Minimal struct {
	conn connection.RegisterConnection
	mode byte
}

// NewBNO055Minimal waits for boot, verifies CHIP_ID and enters NDOF. conn must
// be a configured I²C connection at 0x29 (COM3 high) or 0x28 (COM3 low).
func NewBNO055Minimal(conn connection.RegisterConnection) (*BNO055Minimal, error) {
	d := &BNO055Minimal{conn: conn, mode: bnoModeNDOF}
	if err := d.initialize(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *BNO055Minimal) initialize() error {
	var last error = ErrBNO055ChipID
	found := false
	for i := 0; i < 66; i++ { // the chip does not ACK for up to 650 ms
		id, err := d.r1(regBnoChipID)
		if err == nil && id == bnoChipIDValue {
			found = true
			break
		}
		if err != nil {
			last = err
		} else {
			last = fmt.Errorf("%w, got 0x%02X", ErrBNO055ChipID, id)
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !found {
		return last
	}
	for _, w := range [][2]byte{{regBnoPwrMode, 0x00}, {regBnoPageID, 0x00}, {regBnoUnitSel, bnoUnitSelSI}, {regBnoOprMode, bnoModeNDOF}} {
		if err := d.w(w[0], w[1]); err != nil {
			return err
		}
	}
	d.mode = bnoModeNDOF
	time.Sleep(7 * time.Millisecond)
	return nil
}

func (d *BNO055Minimal) w(reg, value byte) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

func (d *BNO055Minimal) r1(reg byte) (byte, error) {
	b, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *BNO055Minimal) vector(reg byte, scale float32) (float32, float32, float32, error) {
	b, err := d.conn.ReadReg(uint32(reg), 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return float32(le16(b[0:])) / scale, float32(le16(b[2:])) / scale, float32(le16(b[4:])) / scale, nil
}

// Euler reads the fused Euler angles (one burst of 0x1A–0x1F, raw / 900) and
// returns heading, roll and pitch in rad.
func (d *BNO055Minimal) Euler() (heading, roll, pitch float32, err error) {
	return d.vector(regBnoEulData, 900)
}

// BNO055Full is the BNO055 full driver — all outputs, calibration, modes and
// interrupts. It embeds BNO055Minimal, whose methods it promotes.
type BNO055Full struct {
	*BNO055Minimal

	clkSel byte

	mu          sync.Mutex
	callback    func(byte)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewBNO055Full creates the Full driver; conn is as for NewBNO055Minimal.
func NewBNO055Full(conn connection.RegisterConnection) (*BNO055Full, error) {
	m, err := NewBNO055Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &BNO055Full{BNO055Minimal: m}, nil
}

func (d *BNO055Full) enterConfig() (byte, error) {
	prev := d.mode
	if err := d.w(regBnoOprMode, bnoModeConfig); err != nil {
		return prev, err
	}
	time.Sleep(19 * time.Millisecond)
	return prev, nil
}

func (d *BNO055Full) leaveConfig(mode byte) error {
	if mode == bnoModeConfig {
		return nil
	}
	if err := d.w(regBnoOprMode, mode); err != nil {
		return err
	}
	time.Sleep(7 * time.Millisecond)
	return nil
}

// inConfig runs fn in CONFIGMODE and restores the previous operation mode.
func (d *BNO055Full) inConfig(fn func() error) error {
	prev, err := d.enterConfig()
	if err != nil {
		return err
	}
	ferr := fn()
	if lerr := d.leaveConfig(prev); ferr == nil {
		ferr = lerr
	}
	return ferr
}

// SetMode selects the operation mode (0 CONFIGMODE … 12 NDOF) through
// CONFIGMODE, with the 19 ms and 7 ms waits.
func (d *BNO055Full) SetMode(mode byte) error {
	if mode > BNO055ModeNDOF {
		return errors.New("BNO055: mode must be 0..12")
	}
	if err := d.w(regBnoOprMode, bnoModeConfig); err != nil {
		return err
	}
	time.Sleep(19 * time.Millisecond)
	if mode != bnoModeConfig {
		if err := d.w(regBnoOprMode, mode); err != nil {
			return err
		}
		time.Sleep(7 * time.Millisecond)
	}
	d.mode = mode
	return nil
}

// GetMode reads OPR_MODE (0–12).
func (d *BNO055Full) GetMode() (byte, error) {
	v, err := d.r1(regBnoOprMode)
	return v & 0x0F, err
}

// Quaternion reads the fused orientation quaternion (raw / 16384).
func (d *BNO055Full) Quaternion() (w, x, y, z float32, err error) {
	b, err := d.conn.ReadReg(regBnoQuaData, 8)
	if err != nil {
		return 0, 0, 0, 0, err
	}
	return float32(le16(b[0:])) / 16384, float32(le16(b[2:])) / 16384, float32(le16(b[4:])) / 16384, float32(le16(b[6:])) / 16384, nil
}

// LinearAcceleration reads the gravity-free linear acceleration in m/s² (raw / 100).
func (d *BNO055Full) LinearAcceleration() (x, y, z float32, err error) {
	return d.vector(regBnoLiaData, 100)
}

// Gravity reads the gravity vector in m/s² (raw / 100).
func (d *BNO055Full) Gravity() (x, y, z float32, err error) {
	return d.vector(regBnoGrvData, 100)
}

// Acceleration reads the raw accelerometer in m/s² (raw / 100).
func (d *BNO055Full) Acceleration() (x, y, z float32, err error) {
	return d.vector(regBnoAccData, 100)
}

// MagneticField reads the raw magnetometer in µT (raw / 16).
func (d *BNO055Full) MagneticField() (x, y, z float32, err error) {
	return d.vector(regBnoMagData, 16)
}

// AngularRate reads the raw gyroscope in rad/s (raw / 900).
func (d *BNO055Full) AngularRate() (x, y, z float32, err error) {
	return d.vector(regBnoGyrData, 900)
}

// Temperature reads the temperature in °C (signed 8 bit, 1 LSB = 1 °C).
func (d *BNO055Full) Temperature() (int8, error) {
	v, err := d.r1(regBnoTemp)
	return int8(v), err
}

// CalibrationStatus reads CALIB_STAT: each value is 0 (uncalibrated) … 3 (fully calibrated).
func (d *BNO055Full) CalibrationStatus() (sys, gyr, acc, mag uint8, err error) {
	v, err := d.r1(regBnoCalibStat)
	return v >> 6 & 3, v >> 4 & 3, v >> 2 & 3, v & 3, err
}

// IsCalibrated reports whether sys, gyr, acc and mag are all 3.
func (d *BNO055Full) IsCalibrated() (bool, error) {
	v, err := d.r1(regBnoCalibStat)
	return v == 0xFF, err
}

// GetCalibration reads the 22 offset/radius bytes 0x55–0x6A in CONFIGMODE
// (the previous mode is restored).
func (d *BNO055Full) GetCalibration() ([]byte, error) {
	var out []byte
	err := d.inConfig(func() error {
		b, err := d.conn.ReadReg(regBnoAccOffset, 22)
		out = b
		return err
	})
	return out, err
}

// SetCalibration writes the 22 offset/radius bytes in CONFIGMODE (the
// previous mode is restored).
func (d *BNO055Full) SetCalibration(data []byte) error {
	if len(data) != 22 {
		return errors.New("BNO055: calibration data must be 22 bytes")
	}
	return d.inConfig(func() error {
		for i, b := range data {
			if err := d.w(byte(regBnoAccOffset+i), b); err != nil {
				return err
			}
		}
		return nil
	})
}

// SetAxisRemap writes AXIS_MAP_CONFIG and AXIS_MAP_SIGN in CONFIGMODE.
func (d *BNO055Full) SetAxisRemap(config, sign byte) error {
	return d.inConfig(func() error {
		if err := d.w(regBnoAxisMapCfg, config); err != nil {
			return err
		}
		return d.w(regBnoAxisMapSign, sign)
	})
}

// SetPowerMode selects the power mode (BNO055Power*) in CONFIGMODE.
func (d *BNO055Full) SetPowerMode(mode byte) error {
	if mode > BNO055PowerSuspend {
		return errors.New("BNO055: invalid power mode")
	}
	return d.inConfig(func() error { return d.w(regBnoPwrMode, mode) })
}

// SetTemperatureSource selects the temperature sensor (BNO055TempSource*) in CONFIGMODE.
func (d *BNO055Full) SetTemperatureSource(source byte) error {
	if source > BNO055TempSourceGyroscope {
		return errors.New("BNO055: invalid temperature source")
	}
	return d.inConfig(func() error { return d.w(regBnoTempSource, source) })
}

// UseExternalCrystal selects the external 32.768 kHz crystal (only if fitted)
// or the internal oscillator, in CONFIGMODE.
func (d *BNO055Full) UseExternalCrystal(on bool) error {
	return d.inConfig(func() error {
		d.clkSel = 0
		if on {
			d.clkSel = 0x80
		}
		return d.w(regBnoSysTrigger, d.clkSel)
	})
}

// SelfTest runs the power-on self-test (CONFIGMODE, mode restored, waits 500 ms).
func (d *BNO055Full) SelfTest() (BNO055SelfTestResult, error) {
	var v byte
	err := d.inConfig(func() error {
		if err := d.w(regBnoSysTrigger, d.clkSel|0x01); err != nil {
			return err
		}
		time.Sleep(500 * time.Millisecond)
		var rerr error
		v, rerr = d.r1(regBnoStResult)
		return rerr
	})
	return BNO055SelfTestResult{Acc: v&1 != 0, Mag: v&2 != 0, Gyr: v&4 != 0, MCU: v&8 != 0}, err
}

// SystemStatus reads SYS_STATUS (5 = fusion running) and SYS_ERR (0 = none).
func (d *BNO055Full) SystemStatus() (status, errCode uint8, err error) {
	b, err := d.conn.ReadReg(regBnoSysStatus, 2)
	if err != nil {
		return 0, 0, err
	}
	return b[0], b[1], nil
}

// Reset resets the chip (RST_SYS), waits 650 ms and re-initialises into NDOF.
func (d *BNO055Full) Reset() error {
	if err := d.w(regBnoSysTrigger, d.clkSel|0x20); err != nil {
		return err
	}
	time.Sleep(650 * time.Millisecond)
	d.clkSel = 0
	return d.initialize()
}

// Revisions reads the identification and revision registers 0x00–0x06.
func (d *BNO055Full) Revisions() (BNO055Revisions, error) {
	b, err := d.conn.ReadReg(regBnoChipID, 7)
	if err != nil {
		return BNO055Revisions{}, err
	}
	return BNO055Revisions{ChipID: b[0], AccID: b[1], MagID: b[2], GyrID: b[3],
		SwRevision: uint16(b[4]) | uint16(b[5])<<8, Bootloader: b[6]}, nil
}

// UniqueID reads the 16-byte unique ID from page 1 (page 0 is restored).
func (d *BNO055Full) UniqueID() ([]byte, error) {
	if err := d.w(regBnoPageID, 0x01); err != nil {
		return nil, err
	}
	b, rerr := d.conn.ReadReg(regBnoUniqueID, 16)
	if err := d.w(regBnoPageID, 0x00); err != nil {
		return nil, err
	}
	return b, rerr
}

func (d *BNO055Full) updateIntReg(source byte, enable bool) error {
	if err := d.w(regBnoPageID, 0x01); err != nil {
		return err
	}
	var rerr error
	for _, reg := range []byte{regBnoIntEn, regBnoIntMsk} {
		v, err := d.r1(reg)
		if err == nil {
			if enable {
				v |= source
			} else {
				v &^= source
			}
			err = d.w(reg, v)
		}
		if err != nil {
			rerr = err
			break
		}
	}
	if err := d.w(regBnoPageID, 0x00); err != nil {
		return err
	}
	return rerr
}

// EnableInterrupt sets source bits (BNO055Source*) in INT_EN and INT_MSK (page 1).
func (d *BNO055Full) EnableInterrupt(source byte) error { return d.updateIntReg(source, true) }

// DisableInterrupt clears source bits in INT_EN and INT_MSK.
func (d *BNO055Full) DisableInterrupt(source byte) error { return d.updateIntReg(source, false) }

// PollInterrupt reads INT_STA (cleared on read) as BNO055Source* bits.
func (d *BNO055Full) PollInterrupt() (byte, error) { return d.r1(regBnoIntSta) }

// OnInterrupt subscribes callback to rising edges of the INT pin. The callback
// receives the PollInterrupt status and is only called for a non-zero status.
// Delivery uses the connection's IntPin when one is wired and falls back to a
// 100 ms polling goroutine otherwise.
func (d *BNO055Full) OnInterrupt(callback func(byte)) error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()

	var pin connection.InputPin = d.conn.IntPin()
	if d.conn.IntPin() == nil {
		poll := connection.NewPollingInputPin(100 * time.Millisecond)
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
func (d *BNO055Full) OffInterrupt() error {
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

func (d *BNO055Full) handleEdge() {
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

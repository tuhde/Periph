package humidity

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// SHTC3 command codes (16-bit, sent MSB first; the chip has no register pointer).
const (
	shtc3CmdSleep  = 0xB098
	shtc3CmdWake   = 0x3517
	shtc3CmdReset  = 0x805D
	shtc3CmdReadID = 0xEFC8
	shtc3IDMask    = 0x083F
	shtc3IDValue   = 0x0807
)

// Measurement commands [lowPower][clockStretching], temperature first.
var shtc3CmdMeasure = [2][2]uint16{{0x7866, 0x7CA2}, {0x609C, 0x6458}}

// ErrSHTC3CRC is returned when the CRC-8 of a result does not match.
var ErrSHTC3CRC = errors.New("shtc3: CRC mismatch")

// ErrSHTC3WrongChip is returned by Init when the ID word is not an SHTC3.
var ErrSHTC3WrongChip = errors.New("shtc3: wrong product ID")

// SHTC3CRC8 computes the SHTC3 CRC-8 (polynomial 0x31, init 0xFF, no final XOR).
func SHTC3CRC8(data []byte) byte {
	crc := byte(0xFF)
	for _, b := range data {
		crc ^= b
		for i := 0; i < 8; i++ {
			if crc&0x80 != 0 {
				crc = crc<<1 ^ 0x31
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}

func shtc3Humidity(code uint16) float32 {
	rh := 100.0 * float32(code) / 65536.0
	if rh < 0 {
		return 0
	}
	if rh > 100 {
		return 100
	}
	return rh
}

func shtc3Temperature(code uint16) float32 {
	return -45.0 + 175.0*float32(code)/65536.0
}

// shtc3Word checks the CRC of one 3-byte word and returns its value.
func shtc3Word(c []byte) (uint16, error) {
	if SHTC3CRC8(c[0:2]) != c[2] {
		return 0, ErrSHTC3CRC
	}
	return uint16(c[0])<<8 | uint16(c[1]), nil
}

// SHTC3Minimal is the SHTC3 humidity and temperature sensor driver
// (Sensirion) — minimal interface.
//
// Each Read runs a full measurement cycle: wake-up (0x3517), a normal-mode
// temperature-first measurement without clock stretching (0x7866), a 13 ms
// wait, a 6-byte read with both CRC-8 bytes verified, and the sleep command
// (0xB098). A bad CRC is retried once. The chip is command driven, so a plain
// I²C connection is used. The constructor does no bus access; Init performs
// the soft reset and the ID check.
type SHTC3Minimal struct {
	conn         connection.Connection
	lowPower     bool
	stretching   bool
	sleepBetween bool
	asleep       bool
}

// NewSHTC3Minimal creates the driver. conn must be a configured I²C
// connection bound to 0x70.
func NewSHTC3Minimal(conn connection.Connection) *SHTC3Minimal {
	return &SHTC3Minimal{conn: conn, sleepBetween: true}
}

func (d *SHTC3Minimal) command(code uint16) error {
	return d.conn.Write([]byte{byte(code >> 8), byte(code)})
}

func (d *SHTC3Minimal) wakeIfAsleep() error {
	if d.asleep {
		if err := d.command(shtc3CmdWake); err != nil {
			return err
		}
		time.Sleep(time.Millisecond)
		d.asleep = false
	}
	return nil
}

func (d *SHTC3Minimal) goToSleep() error {
	if err := d.command(shtc3CmdSleep); err != nil {
		return err
	}
	d.asleep = true
	return nil
}

func (d *SHTC3Minimal) readIDWord() (uint16, error) {
	if err := d.command(shtc3CmdReadID); err != nil {
		return 0, err
	}
	b, err := d.conn.Read(3)
	if err != nil {
		return 0, err
	}
	return shtc3Word(b)
}

// measure runs one cycle and returns (tRaw, rhRaw); a bad CRC is retried once.
func (d *SHTC3Minimal) measure() (uint16, uint16, error) {
	if err := d.wakeIfAsleep(); err != nil {
		return 0, 0, err
	}
	t, rh, err := d.measureOnce()
	if d.sleepBetween {
		if serr := d.goToSleep(); err == nil {
			err = serr
		}
	}
	return t, rh, err
}

func (d *SHTC3Minimal) measureOnce() (uint16, uint16, error) {
	lp, st := 0, 0
	if d.lowPower {
		lp = 1
	}
	if d.stretching {
		st = 1
	}
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		if err := d.command(shtc3CmdMeasure[lp][st]); err != nil {
			return 0, 0, err
		}
		if !d.stretching {
			if d.lowPower {
				time.Sleep(time.Millisecond)
			} else {
				time.Sleep(13 * time.Millisecond)
			}
		}
		data, err := d.conn.Read(6)
		if err != nil {
			return 0, 0, err
		}
		t, errT := shtc3Word(data[0:3])
		rh, errRH := shtc3Word(data[3:6])
		if errT == nil && errRH == nil {
			return t, rh, nil
		}
		lastErr = ErrSHTC3CRC
	}
	return 0, 0, lastErr
}

// Init wakes the sensor, sends the soft reset (0x805D), checks the product ID
// and puts the sensor to sleep.
func (d *SHTC3Minimal) Init() error {
	d.asleep = true
	if err := d.wakeIfAsleep(); err != nil {
		return err
	}
	if err := d.command(shtc3CmdReset); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	id, idErr := d.readIDWord()
	if err := d.goToSleep(); err != nil {
		return err
	}
	if idErr != nil {
		return idErr
	}
	if id&shtc3IDMask != shtc3IDValue {
		return ErrSHTC3WrongChip
	}
	return nil
}

// Read measures temperature (°C) and relative humidity (%RH, clamped to 0-100).
func (d *SHTC3Minimal) Read() (temperature, humidity float32, err error) {
	t, rh, err := d.measure()
	if err != nil {
		return 0, 0, err
	}
	return shtc3Temperature(t), shtc3Humidity(rh), nil
}

// SHTC3Full is the SHTC3 driver with the complete feature set: single
// values, raw codes, low-power mode, clock stretching, sleep control, ID and
// dew point.
type SHTC3Full struct {
	SHTC3Minimal
}

// NewSHTC3Full creates the full driver; same argument as NewSHTC3Minimal.
func NewSHTC3Full(conn connection.Connection) *SHTC3Full {
	return &SHTC3Full{SHTC3Minimal{conn: conn, sleepBetween: true}}
}

// ReadTemperature runs one measurement cycle and returns °C.
func (d *SHTC3Full) ReadTemperature() (float32, error) {
	t, _, err := d.measure()
	if err != nil {
		return 0, err
	}
	return shtc3Temperature(t), nil
}

// ReadHumidity runs one measurement cycle and returns %RH, clamped to 0-100.
func (d *SHTC3Full) ReadHumidity() (float32, error) {
	_, rh, err := d.measure()
	if err != nil {
		return 0, err
	}
	return shtc3Humidity(rh), nil
}

// ReadRaw runs one measurement cycle and returns the raw 16-bit codes.
func (d *SHTC3Full) ReadRaw() (tRaw, rhRaw uint16, err error) {
	return d.measure()
}

// SetLowPower selects the low-power (0.7 ms, waits 1 ms) or the normal
// (10.8 ms, waits 13 ms) measurement commands.
func (d *SHTC3Full) SetLowPower(on bool) { d.lowPower = on }

// SetClockStretching selects the clock-stretching command set; the fixed wait
// is skipped. It needs a controller that tolerates a slave holding SCL low.
func (d *SHTC3Full) SetClockStretching(on bool) { d.stretching = on }

// SetSleepBetween chooses whether a read ends with the sleep command (default on).
func (d *SHTC3Full) SetSleepBetween(on bool) { d.sleepBetween = on }

// Wake sends the wake-up command (0x3517) and waits 1 ms.
func (d *SHTC3Full) Wake() error {
	if err := d.command(shtc3CmdWake); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	d.asleep = false
	return nil
}

// Sleep sends the sleep command (0xB098).
func (d *SHTC3Full) Sleep() error { return d.goToSleep() }

// SoftReset sends the reset command (0x805D) and waits 1 ms; leaves the sensor idle.
func (d *SHTC3Full) SoftReset() error {
	if err := d.wakeIfAsleep(); err != nil {
		return err
	}
	if err := d.command(shtc3CmdReset); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	return nil
}

// ReadID reads the 16-bit ID word (command 0xEFC8, CRC verified);
// (id & 0x083F) == 0x0807 for the SHTC3. A sleeping sensor is woken for the
// read and put back to sleep.
func (d *SHTC3Full) ReadID() (uint16, error) {
	wasAsleep := d.asleep
	if err := d.wakeIfAsleep(); err != nil {
		return 0, err
	}
	id, err := d.readIDWord()
	if wasAsleep {
		if serr := d.goToSleep(); serr != nil && err == nil {
			err = serr
		}
	}
	return id, err
}

// DewPoint runs one measurement and computes the dew point in °C (Magnus formula over water).
func (d *SHTC3Full) DewPoint() (float32, error) {
	t, rh, err := d.Read()
	if err != nil {
		return 0, err
	}
	r := float64(rh)
	if r <= 0 {
		r = 0.01
	}
	g := math.Log(r/100.0) + 17.62*float64(t)/(243.12+float64(t))
	return float32(243.12 * g / (17.62 - g)), nil
}

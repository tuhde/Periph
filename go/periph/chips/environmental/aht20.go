// Package environmental contains drivers for combined environmental sensors
// (temperature, humidity, pressure).
package environmental

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ErrAHT20NotCalibrated is returned by NewAHT20Minimal / NewAHT20Full when the
// sensor does not report calibrated (status 0x18) after the soft reset and the
// calibration register init.
var ErrAHT20NotCalibrated = errors.New("aht20: not calibrated after init")

// ErrAHT20Timeout is returned when BUSY does not clear within 200 ms after the
// 80 ms measurement wait.
var ErrAHT20Timeout = errors.New("aht20: measurement timeout")

// AHT20 command bytes (the chip uses a command-based protocol, not a
// register map).
var (
	aht20CmdStatus    = []byte{0x71}
	aht20CmdTrigger   = []byte{0xAC, 0x33, 0x00}
	aht20CmdSoftReset = []byte{0xBA}
	aht20CmdCalInit   = [][]byte{{0x1B, 0x00, 0x00}, {0x1C, 0x00, 0x00}, {0x1E, 0x00, 0x00}}
)

const (
	aht20StatusBusy  = 0x80
	aht20StatusCal   = 0x08
	aht20StatusReady = 0x18

	aht20PowerOnDelay   = 100 * time.Millisecond
	aht20InitDelay      = 10 * time.Millisecond
	aht20SoftResetDelay = 20 * time.Millisecond
	aht20MeasureDelay   = 80 * time.Millisecond
	aht20PollStep       = 10 * time.Millisecond
	aht20PollGuard      = 200 * time.Millisecond
)

// AHT20Minimal is the AHT20 temperature and humidity sensor driver — minimal
// interface.
//
// Handles power-on wait, calibration check (soft reset and calibration
// register init if needed) and measurement triggering automatically. The default
// configuration baked in is: one-shot measurement on every Read call, 80 ms
// wait after the trigger followed by BUSY polling (10 ms steps, 200 ms
// guard), no CRC verification (Full only).
type AHT20Minimal struct {
	connection connection.Connection
}

// NewAHT20Minimal creates a new AHT20Minimal and performs power-on
// initialization: 100 ms wait, status check against 0x18, soft reset and
// calibration register init (0x1B/0x1C/0x1E) if needed. Returns
// ErrAHT20NotCalibrated if the sensor is still uncalibrated.
//
// connection must be a configured I²C connection bound to the device's
// 7-bit address (0x38).
func NewAHT20Minimal(t connection.Connection) (*AHT20Minimal, error) {
	d := &AHT20Minimal{connection: t}
	time.Sleep(aht20PowerOnDelay)
	if err := d.calibrate(); err != nil {
		return nil, err
	}
	time.Sleep(aht20InitDelay)
	return d, nil
}

// ready reports whether the status word is 0x18 (idle and calibrated).
func (d *AHT20Minimal) ready() (bool, error) {
	status, err := d.readStatus()
	if err != nil {
		return false, err
	}
	return status&aht20StatusReady == aht20StatusReady, nil
}

// calibrate runs the datasheet calibration check: status word against 0x18,
// then soft reset, then the calibration register init.
func (d *AHT20Minimal) calibrate() error {
	ok, err := d.ready()
	if err != nil || ok {
		return err
	}
	if err := d.connection.Write(aht20CmdSoftReset); err != nil {
		return err
	}
	time.Sleep(aht20SoftResetDelay)
	if ok, err = d.ready(); err != nil || ok {
		return err
	}
	for _, cmd := range aht20CmdCalInit {
		if err := d.connection.Write(cmd); err != nil {
			return err
		}
		time.Sleep(aht20InitDelay)
	}
	if ok, err = d.ready(); err != nil {
		return err
	}
	if !ok {
		return ErrAHT20NotCalibrated
	}
	return nil
}

// readStatus sends the status command (0x71) and reads the 1-byte status.
func (d *AHT20Minimal) readStatus() (byte, error) {
	if err := d.connection.Write(aht20CmdStatus); err != nil {
		return 0, err
	}
	b, err := d.connection.Read(1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// measure triggers a conversion and returns the n-byte frame (6, or 7 with
// CRC) once BUSY is clear.
func (d *AHT20Minimal) measure(n int) ([]byte, error) {
	if err := d.connection.Write(aht20CmdTrigger); err != nil {
		return nil, err
	}
	time.Sleep(aht20MeasureDelay) // conversion takes > 80 ms
	for waited := time.Duration(0); ; waited += aht20PollStep {
		data, err := d.connection.Read(n)
		if err != nil {
			return nil, err
		}
		if data[0]&aht20StatusBusy == 0 {
			return data, nil
		}
		if waited >= aht20PollGuard {
			return nil, ErrAHT20Timeout
		}
		time.Sleep(aht20PollStep)
	}
}

// Read triggers a measurement and returns temperature (°C, -50 to 150) and
// relative humidity (%RH, 0 to 100).
//
// Sends the 0xAC 0x33 0x00 trigger command, waits 80 ms, polls BUSY, reads 6
// bytes and decodes the raw 20-bit values into physical units.
func (d *AHT20Minimal) Read() (temperatureC, humidityPct float32, err error) {
	data, err := d.measure(6)
	if err != nil {
		return 0, 0, err
	}
	rh, t := aht20Raw(data)
	temperatureC, humidityPct = aht20Convert(rh, t)
	return temperatureC, humidityPct, nil
}

// aht20Raw extracts the raw 20-bit humidity and temperature values from the frame.
func aht20Raw(buf []byte) (rawRH, rawT uint32) {
	rawRH = (uint32(buf[1]) << 12) | (uint32(buf[2]) << 4) | (uint32(buf[3]) >> 4)
	rawT = (uint32(buf[3]&0x0F) << 16) | (uint32(buf[4]) << 8) | uint32(buf[5])
	return
}

// aht20Convert converts raw 20-bit values to °C and %RH (humidity clamped to 0–100).
func aht20Convert(rawRH, rawT uint32) (temperatureC, humidityPct float32) {
	humidityPct = (float32(rawRH) / 1048576.0) * 100.0
	if humidityPct > 100.0 {
		humidityPct = 100.0
	}
	temperatureC = (float32(rawT)/1048576.0)*200.0 - 50.0
	return
}

// AHT20Full is the AHT20 temperature and humidity sensor driver — full
// interface. Extends AHT20Minimal with individual readings, CRC, raw values,
// status inspection, explicit soft reset and dew point.
//
// Embeds AHT20Minimal to inherit Read and constructor; exposes
// AHT20Full-only methods below.
type AHT20Full struct {
	*AHT20Minimal
}

// NewAHT20Full creates a new AHT20Full and performs the same power-on
// initialization as NewAHT20Minimal.
func NewAHT20Full(t connection.Connection) (*AHT20Full, error) {
	m, err := NewAHT20Minimal(t)
	if err != nil {
		return nil, err
	}
	return &AHT20Full{AHT20Minimal: m}, nil
}

// ReadTemperature triggers a measurement and returns temperature only.
func (d *AHT20Full) ReadTemperature() (float32, error) {
	t, _, err := d.AHT20Minimal.Read()
	return t, err
}

// ReadHumidity triggers a measurement and returns humidity only.
func (d *AHT20Full) ReadHumidity() (float32, error) {
	_, h, err := d.AHT20Minimal.Read()
	return h, err
}

// ReadRaw triggers a measurement and returns the raw 20-bit humidity and
// temperature values (each 0 to 1048575).
func (d *AHT20Full) ReadRaw() (rawHumidity, rawTemperature uint32, err error) {
	data, err := d.measure(6)
	if err != nil {
		return 0, 0, err
	}
	rawHumidity, rawTemperature = aht20Raw(data)
	return rawHumidity, rawTemperature, nil
}

// ReadWithCRC triggers a measurement, reads 7 bytes and verifies the CRC-8
// (polynomial 0x31, initial value 0xFF, over bytes 0 to 5, compared with byte 6).
func (d *AHT20Full) ReadWithCRC() (temperatureC, humidityPct float32, crcOK bool, err error) {
	data, err := d.measure(7)
	if err != nil {
		return 0, 0, false, err
	}
	rh, t := aht20Raw(data)
	temperatureC, humidityPct = aht20Convert(rh, t)
	return temperatureC, humidityPct, aht20CRC8(data[:6]) == data[6], nil
}

// aht20CRC8 computes the CRC-8 (polynomial 0x31, initial value 0xFF).
func aht20CRC8(data []byte) byte {
	crc := byte(0xFF)
	for _, b := range data {
		crc ^= b
		for i := 0; i < 8; i++ {
			if crc&0x80 != 0 {
				crc = (crc << 1) ^ 0x31
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}

// ReadStatus returns the status byte (bit 7 BUSY, bit 3 CAL).
func (d *AHT20Full) ReadStatus() (byte, error) {
	return d.readStatus()
}

// IsCalibrated returns true if the calibration bit is set in the status byte.
func (d *AHT20Full) IsCalibrated() (bool, error) {
	s, err := d.readStatus()
	if err != nil {
		return false, err
	}
	return s&aht20StatusCal != 0, nil
}

// IsBusy returns true if the busy bit is set in the status byte.
func (d *AHT20Full) IsBusy() (bool, error) {
	s, err := d.readStatus()
	if err != nil {
		return false, err
	}
	return s&aht20StatusBusy != 0, nil
}

// SoftReset sends the soft reset command, waits 20 ms and re-runs the calibration check.
func (d *AHT20Full) SoftReset() error {
	if err := d.connection.Write(aht20CmdSoftReset); err != nil {
		return err
	}
	time.Sleep(aht20SoftResetDelay)
	return d.calibrate()
}

// DewPoint triggers a measurement and returns the dew point in °C (Magnus
// formula). Returns -50 if the humidity is 0 %RH.
func (d *AHT20Full) DewPoint() (float32, error) {
	t, h, err := d.AHT20Minimal.Read()
	if err != nil {
		return 0, err
	}
	if h <= 0 {
		return -50, nil
	}
	gamma := math.Log(float64(h)/100.0) + 17.62*float64(t)/(243.12+float64(t))
	return float32(243.12 * gamma / (17.62 - gamma)), nil
}

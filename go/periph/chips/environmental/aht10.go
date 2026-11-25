// Package environmental contains drivers for combined environmental sensors
// (temperature, humidity, pressure).
package environmental

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ErrAHT10NotCalibrated is returned by NewAHT10Minimal / NewAHT10Full when the
// sensor still reports CAL = 0 after the init command and one soft-reset retry.
var ErrAHT10NotCalibrated = errors.New("aht10: not calibrated after init")

// ErrAHT10Timeout is returned when BUSY does not clear within 200 ms after the
// 80 ms measurement wait.
var ErrAHT10Timeout = errors.New("aht10: measurement timeout")

// AHT10 command bytes (the chip uses a command-based protocol, not a
// register map).
var (
	aht10CmdInit      = []byte{0xE1, 0x08, 0x00}
	aht10CmdTrigger   = []byte{0xAC, 0x33, 0x00}
	aht10CmdSoftReset = []byte{0xBA}
)

const (
	aht10StatusBusy = 0x80
	aht10StatusCal  = 0x08

	aht10PowerOnDelay   = 40 * time.Millisecond
	aht10InitDelay      = 10 * time.Millisecond
	aht10SoftResetDelay = 20 * time.Millisecond
	aht10MeasureDelay   = 80 * time.Millisecond
	aht10PollStep       = 10 * time.Millisecond
	aht10PollGuard      = 200 * time.Millisecond
)

// AHT10Minimal is the AHT10 temperature and humidity sensor driver — minimal
// interface.
//
// Handles power-on wait, initialization (0xE1 calibration enable),
// calibration check and measurement triggering automatically. The default
// configuration baked in is: one-shot measurement on every Read call, 80 ms
// wait after the trigger followed by BUSY polling (10 ms steps, 200 ms
// guard), no CRC (the AHT10 does not send one).
type AHT10Minimal struct {
	connection connection.Connection
}

// NewAHT10Minimal creates a new AHT10Minimal and performs power-on
// initialization: 40 ms wait, init command 0xE1 0x08 0x00, calibration check
// (soft reset and one retry if CAL is clear). Returns ErrAHT10NotCalibrated
// if the sensor is still uncalibrated.
//
// connection must be a configured I²C connection bound to the device's
// 7-bit address (0x38).
func NewAHT10Minimal(t connection.Connection) (*AHT10Minimal, error) {
	d := &AHT10Minimal{connection: t}
	time.Sleep(aht10PowerOnDelay)
	if err := d.initialize(); err != nil {
		return nil, err
	}
	status, err := d.readStatus()
	if err != nil {
		return nil, err
	}
	if status&aht10StatusCal == 0 {
		if err := d.connection.Write(aht10CmdSoftReset); err != nil {
			return nil, err
		}
		time.Sleep(aht10SoftResetDelay)
		if err := d.initialize(); err != nil {
			return nil, err
		}
		status, err = d.readStatus()
		if err != nil {
			return nil, err
		}
		if status&aht10StatusCal == 0 {
			return nil, ErrAHT10NotCalibrated
		}
	}
	return d, nil
}

// initialize sends the init (calibration enable) command and waits for it to settle.
func (d *AHT10Minimal) initialize() error {
	if err := d.connection.Write(aht10CmdInit); err != nil {
		return err
	}
	time.Sleep(aht10InitDelay)
	return nil
}

// readStatus reads the 1-byte status register.
func (d *AHT10Minimal) readStatus() (byte, error) {
	b, err := d.connection.Read(1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// measure triggers a conversion and returns the 6-byte frame once BUSY is clear.
func (d *AHT10Minimal) measure() ([]byte, error) {
	if err := d.connection.Write(aht10CmdTrigger); err != nil {
		return nil, err
	}
	time.Sleep(aht10MeasureDelay) // conversion takes > 75 ms
	for waited := time.Duration(0); ; waited += aht10PollStep {
		data, err := d.connection.Read(6)
		if err != nil {
			return nil, err
		}
		if data[0]&aht10StatusBusy == 0 {
			return data, nil
		}
		if waited >= aht10PollGuard {
			return nil, ErrAHT10Timeout
		}
		time.Sleep(aht10PollStep)
	}
}

// Read triggers a measurement and returns temperature (°C, -50 to 150) and
// relative humidity (%RH, 0 to 100).
//
// Sends the 0xAC 0x33 0x00 trigger command, waits 80 ms, polls BUSY, reads 6
// bytes and decodes the raw 20-bit values into physical units.
func (d *AHT10Minimal) Read() (temperatureC, humidityPct float32, err error) {
	data, err := d.measure()
	if err != nil {
		return 0, 0, err
	}
	rh, t := aht10Raw(data)
	temperatureC, humidityPct = aht10Convert(rh, t)
	return temperatureC, humidityPct, nil
}

// aht10Raw extracts the raw 20-bit humidity and temperature values from the frame.
func aht10Raw(buf []byte) (rawRH, rawT uint32) {
	rawRH = (uint32(buf[1]) << 12) | (uint32(buf[2]) << 4) | (uint32(buf[3]) >> 4)
	rawT = (uint32(buf[3]&0x0F) << 16) | (uint32(buf[4]) << 8) | uint32(buf[5])
	return
}

// aht10Convert converts raw 20-bit values to °C and %RH (humidity clamped to 0–100).
func aht10Convert(rawRH, rawT uint32) (temperatureC, humidityPct float32) {
	humidityPct = (float32(rawRH) / 1048576.0) * 100.0
	if humidityPct > 100.0 {
		humidityPct = 100.0
	}
	temperatureC = (float32(rawT)/1048576.0)*200.0 - 50.0
	return
}

// AHT10Full is the AHT10 temperature and humidity sensor driver — full
// interface. Extends AHT10Minimal with individual readings, raw values,
// status inspection, explicit soft reset and dew point.
//
// Embeds AHT10Minimal to inherit Read and constructor; exposes
// AHT10Full-only methods below.
type AHT10Full struct {
	*AHT10Minimal
}

// NewAHT10Full creates a new AHT10Full and performs the same power-on
// initialization as NewAHT10Minimal.
func NewAHT10Full(t connection.Connection) (*AHT10Full, error) {
	m, err := NewAHT10Minimal(t)
	if err != nil {
		return nil, err
	}
	return &AHT10Full{AHT10Minimal: m}, nil
}

// ReadTemperature triggers a measurement and returns temperature only.
func (d *AHT10Full) ReadTemperature() (float32, error) {
	t, _, err := d.AHT10Minimal.Read()
	return t, err
}

// ReadHumidity triggers a measurement and returns humidity only.
func (d *AHT10Full) ReadHumidity() (float32, error) {
	_, h, err := d.AHT10Minimal.Read()
	return h, err
}

// ReadRaw triggers a measurement and returns the raw 20-bit humidity and
// temperature values (each 0 to 1048575).
func (d *AHT10Full) ReadRaw() (rawHumidity, rawTemperature uint32, err error) {
	data, err := d.measure()
	if err != nil {
		return 0, 0, err
	}
	rawHumidity, rawTemperature = aht10Raw(data)
	return rawHumidity, rawTemperature, nil
}

// ReadStatus returns the status byte (bit 7 BUSY, bits 6:5 MODE, bit 3 CAL).
func (d *AHT10Full) ReadStatus() (byte, error) {
	return d.readStatus()
}

// IsCalibrated returns true if the calibration bit is set in the status byte.
func (d *AHT10Full) IsCalibrated() (bool, error) {
	s, err := d.readStatus()
	if err != nil {
		return false, err
	}
	return s&aht10StatusCal != 0, nil
}

// IsBusy returns true if the busy bit is set in the status byte.
func (d *AHT10Full) IsBusy() (bool, error) {
	s, err := d.readStatus()
	if err != nil {
		return false, err
	}
	return s&aht10StatusBusy != 0, nil
}

// SoftReset sends the soft reset command, waits 20 ms and re-runs the init command.
func (d *AHT10Full) SoftReset() error {
	if err := d.connection.Write(aht10CmdSoftReset); err != nil {
		return err
	}
	time.Sleep(aht10SoftResetDelay)
	return d.initialize()
}

// DewPoint triggers a measurement and returns the dew point in °C (Magnus
// formula). Returns -50 if the humidity is 0 %RH.
func (d *AHT10Full) DewPoint() (float32, error) {
	t, h, err := d.AHT10Minimal.Read()
	if err != nil {
		return 0, err
	}
	if h <= 0 {
		return -50, nil
	}
	gamma := math.Log(float64(h)/100.0) + 17.62*float64(t)/(243.12+float64(t))
	return float32(243.12 * gamma / (17.62 - gamma)), nil
}

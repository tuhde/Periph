// Package environmental contains drivers for combined environmental sensors
// (temperature, humidity, pressure).
package environmental

import (
	"time"

	"github.com/tuhde/Periph/go/periph/transport"
)

// AHT21 command bytes (the chip uses a command-based protocol, not a
// register map).
var (
	aht21CmdTrigger    = []byte{0xAC, 0x33, 0x00}
	aht21CmdSoftReset  = []byte{0xBA}
	aht21CmdCalInit1   = []byte{0x1B, 0x00, 0x00}
	aht21CmdCalInit2   = []byte{0x1C, 0x00, 0x00}
	aht21CmdCalInit3   = []byte{0x1E, 0x00, 0x00}
)

const (
	aht21StatusBusy = 0x80
	aht21StatusCal  = 0x08

	aht21PowerOnDelay   = 100 * time.Millisecond
	aht21SoftResetDelay = 20 * time.Millisecond
	aht21CalStepDelay   = 10 * time.Millisecond
	aht21MeasureDelay   = 80 * time.Millisecond
)

// AHT21Minimal is the AHT21 temperature and humidity sensor driver — minimal
// interface.
//
// Handles power-on initialization, calibration check, and measurement
// triggering automatically. The default configuration baked in is:
// measurement triggered on every Read call, 80 ms fixed wait after
// trigger, no CRC verification.
type AHT21Minimal struct {
	transport transport.Transport
}

// NewAHT21Minimal creates a new AHT21Minimal and performs power-on
// initialization (≥100 ms power-on wait, status check, soft reset and
// calibration init sequence if the device is not yet calibrated).
//
// transport must be a configured I²C transport bound to the device's
// 7-bit address (0x38, fixed).
func NewAHT21Minimal(t transport.Transport) (*AHT21Minimal, error) {
	d := &AHT21Minimal{transport: t}
	time.Sleep(aht21PowerOnDelay)
	status, err := d.readStatus()
	if err != nil {
		return nil, err
	}
	if (status & 0x18) != 0x18 {
		if err := d.transport.Write(aht21CmdSoftReset); err != nil {
			return nil, err
		}
		time.Sleep(aht21SoftResetDelay)
		status, err = d.readStatus()
		if err != nil {
			return nil, err
		}
		if (status & 0x18) != 0x18 {
			for _, cmd := range [][]byte{aht21CmdCalInit1, aht21CmdCalInit2, aht21CmdCalInit3} {
				if err := d.transport.Write(cmd); err != nil {
					return nil, err
				}
				time.Sleep(aht21CalStepDelay)
			}
		}
	}
	return d, nil
}

// readStatus reads the 1-byte status register.
func (d *AHT21Minimal) readStatus() (byte, error) {
	b, err := d.transport.Read(1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// Read triggers a measurement and returns temperature and humidity.
//
// Sends the 0xAC 0x33 0x00 trigger command, waits 80 ms, reads 6 bytes,
// and decodes the raw 20-bit values into physical units.
func (d *AHT21Minimal) Read() (temperatureC, humidityPct float32, err error) {
	if err := d.transport.Write(aht21CmdTrigger); err != nil {
		return 0, 0, err
	}
	time.Sleep(aht21MeasureDelay)
	data, err := d.transport.Read(6)
	if err != nil {
		return 0, 0, err
	}
	t, h := decode(data)
	return t, h, nil
}

// decode extracts temperature and humidity from the 6-byte response frame.
func decode(buf []byte) (temperatureC, humidityPct float32) {
	rawRH := (uint32(buf[1]) << 12) | (uint32(buf[2]) << 4) | (uint32(buf[3]) >> 4)
	rawT := (uint32(buf[3]&0x0F) << 16) | (uint32(buf[4]) << 8) | uint32(buf[5])
	humidityPct = (float32(rawRH) / 1048576.0) * 100.0
	temperatureC = (float32(rawT)/1048576.0)*200.0 - 50.0
	return
}

// AHT21Full is the AHT21 temperature and humidity sensor driver — full
// interface. Extends AHT21Minimal with CRC-8 verification, explicit
// soft reset, calibration status inspection, and individual
// temperature/humidity readings.
//
// Embeds AHT21Minimal to inherit Read and constructor; exposes
// AHT21Full-only methods below.
type AHT21Full struct {
	*AHT21Minimal
}

// NewAHT21Full creates a new AHT21Full and performs the same power-on
// initialization as NewAHT21Minimal.
func NewAHT21Full(t transport.Transport) (*AHT21Full, error) {
	m, err := NewAHT21Minimal(t)
	if err != nil {
		return nil, err
	}
	return &AHT21Full{AHT21Minimal: m}, nil
}

// ReadTemperature triggers a measurement and returns temperature only.
func (d *AHT21Full) ReadTemperature() (float32, error) {
	t, _, err := d.AHT21Minimal.Read()
	return t, err
}

// ReadHumidity triggers a measurement and returns humidity only.
func (d *AHT21Full) ReadHumidity() (float32, error) {
	_, h, err := d.AHT21Minimal.Read()
	return h, err
}

// ReadWithCrc triggers a measurement, reads 7 bytes, and verifies CRC-8.
// Returns (temperature_c, humidity_pct, crc_ok). The CRC-8 polynomial is
// x^8 + x^5 + x^4 + 1 (0x31) with initial value 0xFF, covering bytes 0–5
// of the response frame.
func (d *AHT21Full) ReadWithCrc() (temperatureC, humidityPct float32, crcOk bool, err error) {
	if err := d.transport.Write(aht21CmdTrigger); err != nil {
		return 0, 0, false, err
	}
	time.Sleep(aht21MeasureDelay)
	data, err := d.transport.Read(7)
	if err != nil {
		return 0, 0, false, err
	}
	t, h := decode(data)
	return t, h, crc8(data[0:6]) == data[6], nil
}

// SoftReset sends the soft reset command and waits 20 ms for recovery.
func (d *AHT21Full) SoftReset() error {
	if err := d.transport.Write(aht21CmdSoftReset); err != nil {
		return err
	}
	time.Sleep(aht21SoftResetDelay)
	return nil
}

// IsCalibrated returns true if the calibration bit is set in the status byte.
func (d *AHT21Full) IsCalibrated() (bool, error) {
	s, err := d.readStatus()
	if err != nil {
		return false, err
	}
	return s&aht21StatusCal != 0, nil
}

// IsBusy returns true if the busy bit is set in the status byte.
func (d *AHT21Full) IsBusy() (bool, error) {
	s, err := d.readStatus()
	if err != nil {
		return false, err
	}
	return s&aht21StatusBusy != 0, nil
}

// crc8 computes the AHT21 CRC-8 over the given data.
func crc8(data []byte) byte {
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

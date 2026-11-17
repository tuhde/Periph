package humidity

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HDC1080 register pointers and configuration bits.
const (
	hdc1080RegTemperature    = 0x00
	hdc1080RegHumidity       = 0x01
	hdc1080RegConfig         = 0x02
	hdc1080RegSerial1        = 0xFB
	hdc1080RegManufacturerID = 0xFE
	hdc1080RegDeviceID       = 0xFF
	hdc1080CfgRST            = 0x8000
	hdc1080CfgHeat           = 0x2000
	hdc1080CfgMode           = 0x1000
	hdc1080CfgBTST           = 0x0800
	hdc1080CfgTRES           = 0x0400
	hdc1080CfgHRES           = 0x0300
	hdc1080ConfigDefault     = 0x1000
	hdc1080PollAttempts      = 50
)

// ErrHDC1080Timeout is returned when the result is not ready within 50 ms.
var ErrHDC1080Timeout = errors.New("hdc1080: measurement did not complete")

// ErrHDC1080Resolution is returned by SetResolution for unsupported bit counts.
var ErrHDC1080Resolution = errors.New("hdc1080: temperature bits must be 14 or 11, humidity bits 14, 11 or 8")

func hdc1080Temperature(raw uint16) float32 { return float32(raw)/65536.0*165.0 - 40.0 }
func hdc1080Humidity(raw uint16) float32    { return float32(raw) / 65536.0 * 100.0 }

// HDC1080Minimal is the HDC1080 humidity and temperature sensor driver
// (Texas Instruments) — minimal interface.
//
// Init writes the configuration 0x1000 (14-bit T and RH, sequence mode, heater
// off). Read triggers one sequence measurement (pointer 0x00), polls the NACKed
// read header (timeout 50 ms) and converts the temperature and humidity words.
// The constructor does no bus access.
type HDC1080Minimal struct {
	conn   connection.Connection
	config uint16
}

// NewHDC1080Minimal creates the driver. conn must be a configured plain I²C
// connection bound to 0x40.
func NewHDC1080Minimal(conn connection.Connection) *HDC1080Minimal {
	return &HDC1080Minimal{conn: conn, config: hdc1080ConfigDefault}
}

func (d *HDC1080Minimal) writeConfig() error {
	return d.conn.Write([]byte{hdc1080RegConfig, byte(d.config >> 8), byte(d.config)})
}

// Init writes the default configuration 0x1000.
func (d *HDC1080Minimal) Init() error {
	d.config = hdc1080ConfigDefault
	return d.writeConfig()
}

func (d *HDC1080Minimal) triggerRead(pointer byte, n int) ([]byte, error) {
	if err := d.conn.Write([]byte{pointer}); err != nil {
		return nil, err
	}
	for i := 0; i < hdc1080PollAttempts; i++ {
		data, err := d.conn.Read(n)
		if err == nil {
			return data, nil
		}
		time.Sleep(time.Millisecond) // read header NACKed: not ready yet
	}
	return nil, ErrHDC1080Timeout
}

func (d *HDC1080Minimal) setMode(sequence bool) error {
	want := d.config &^ hdc1080CfgMode
	if sequence {
		want = d.config | hdc1080CfgMode
	}
	if want != d.config {
		d.config = want
		return d.writeConfig()
	}
	return nil
}

func (d *HDC1080Minimal) measurePair() (uint16, uint16, error) {
	if err := d.setMode(true); err != nil {
		return 0, 0, err
	}
	b, err := d.triggerRead(hdc1080RegTemperature, 4)
	if err != nil {
		return 0, 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), uint16(b[2])<<8 | uint16(b[3]), nil
}

// Read measures temperature and humidity (sequence mode, temperature first)
// and returns °C and %RH.
func (d *HDC1080Minimal) Read() (float32, float32, error) {
	t, h, err := d.measurePair()
	if err != nil {
		return 0, 0, err
	}
	return hdc1080Temperature(t), hdc1080Humidity(h), nil
}

// HDC1080Full is the full HDC1080 driver: single values, resolution, heater,
// battery flag, serial ID and the manufacturer/device ID registers. It extends
// HDC1080Minimal.
type HDC1080Full struct {
	*HDC1080Minimal
}

// NewHDC1080Full creates the full driver; see NewHDC1080Minimal.
func NewHDC1080Full(conn connection.Connection) *HDC1080Full {
	return &HDC1080Full{HDC1080Minimal: NewHDC1080Minimal(conn)}
}

// ReadRaw measures and returns the raw 16-bit words (bits 1:0 zero).
func (d *HDC1080Full) ReadRaw() (uint16, uint16, error) { return d.measurePair() }

// ReadTemperature measures temperature only (single mode) in °C.
func (d *HDC1080Full) ReadTemperature() (float32, error) {
	if err := d.setMode(false); err != nil {
		return 0, err
	}
	b, err := d.triggerRead(hdc1080RegTemperature, 2)
	if err != nil {
		return 0, err
	}
	return hdc1080Temperature(uint16(b[0])<<8 | uint16(b[1])), nil
}

// ReadHumidity measures humidity only (single mode) in %RH.
func (d *HDC1080Full) ReadHumidity() (float32, error) {
	if err := d.setMode(false); err != nil {
		return 0, err
	}
	b, err := d.triggerRead(hdc1080RegHumidity, 2)
	if err != nil {
		return 0, err
	}
	return hdc1080Humidity(uint16(b[0])<<8 | uint16(b[1])), nil
}

// SetResolution selects the conversion resolutions: temperature 14 or 11 bit,
// humidity 14, 11 or 8 bit. ErrHDC1080Resolution for other values.
func (d *HDC1080Full) SetResolution(temperatureBits, humidityBits int) error {
	var t, h uint16
	switch temperatureBits {
	case 14:
	case 11:
		t = hdc1080CfgTRES
	default:
		return ErrHDC1080Resolution
	}
	switch humidityBits {
	case 14:
	case 11:
		h = 0x0100
	case 8:
		h = 0x0200
	default:
		return ErrHDC1080Resolution
	}
	d.config = d.config&^(hdc1080CfgTRES|hdc1080CfgHRES) | t | h
	return d.writeConfig()
}

// SetHeater enables or disables the heater.
func (d *HDC1080Full) SetHeater(on bool) error {
	if on {
		d.config |= hdc1080CfgHeat
	} else {
		d.config &^= hdc1080CfgHeat
	}
	return d.writeConfig()
}

// ReadConfig reads the 16-bit configuration register.
func (d *HDC1080Full) ReadConfig() (uint16, error) {
	if err := d.conn.Write([]byte{hdc1080RegConfig}); err != nil {
		return 0, err
	}
	b, err := d.conn.Read(2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

// IsHeaterOn reports whether the heater bit is set in the configuration register.
func (d *HDC1080Full) IsHeaterOn() (bool, error) {
	c, err := d.ReadConfig()
	return c&hdc1080CfgHeat != 0, err
}

// BatteryLow reports whether VDD is below 2.8 V (BTST).
func (d *HDC1080Full) BatteryLow() (bool, error) {
	c, err := d.ReadConfig()
	return c&hdc1080CfgBTST != 0, err
}

// SoftwareReset sets RST, waits 15 ms and rewrites the stored configuration.
func (d *HDC1080Full) SoftwareReset() error {
	if err := d.conn.Write([]byte{hdc1080RegConfig, byte(hdc1080CfgRST >> 8), 0x00}); err != nil {
		return err
	}
	time.Sleep(15 * time.Millisecond)
	return d.writeConfig()
}

// SerialID reads the 40-bit serial number from registers 0xFB–0xFD.
func (d *HDC1080Full) SerialID() (uint64, error) {
	var w [3]uint64
	for i := range w {
		if err := d.conn.Write([]byte{byte(hdc1080RegSerial1 + i)}); err != nil {
			return 0, err
		}
		b, err := d.conn.Read(2)
		if err != nil {
			return 0, err
		}
		w[i] = uint64(b[0])<<8 | uint64(b[1])
	}
	return w[0]<<24 | w[1]<<8 | w[2]>>7, nil
}

// ConversionTime returns the typical conversion time in seconds for the
// current resolutions (temperature + humidity).
func (d *HDC1080Full) ConversionTime() float32 {
	t := float32(0.00635)
	if d.config&hdc1080CfgTRES != 0 {
		t = 0.00365
	}
	switch d.config & hdc1080CfgHRES {
	case 0x0100:
		return t + 0.00385
	case 0x0200:
		return t + 0.0025
	}
	return t + 0.0065
}

func (d *HDC1080Full) readWord(reg byte) (uint16, error) {
	if err := d.conn.Write([]byte{reg}); err != nil {
		return 0, err
	}
	b, err := d.conn.Read(2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

// ManufacturerID reads the manufacturer ID register 0xFE (0x5449 = Texas Instruments).
func (d *HDC1080Full) ManufacturerID() (uint16, error) { return d.readWord(hdc1080RegManufacturerID) }

// DeviceID reads the device ID register 0xFF (0x1050 for the HDC1080).
func (d *HDC1080Full) DeviceID() (uint16, error) { return d.readWord(hdc1080RegDeviceID) }

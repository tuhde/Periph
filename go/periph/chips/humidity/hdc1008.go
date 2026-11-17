package humidity

import (
	"errors"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HDC1008 register pointers and configuration bits.
const (
	hdc1008RegTemperature = 0x00
	hdc1008RegHumidity    = 0x01
	hdc1008RegConfig      = 0x02
	hdc1008RegSerial1     = 0xFB
	hdc1008CfgRST         = 0x8000
	hdc1008CfgHeat        = 0x2000
	hdc1008CfgMode        = 0x1000
	hdc1008CfgBTST        = 0x0800
	hdc1008CfgTRES        = 0x0400
	hdc1008CfgHRES        = 0x0300
	hdc1008ConfigDefault  = 0x1000
	hdc1008PollAttempts   = 50
)

// ErrHDC1008Timeout is returned when the result is not ready within 50 ms.
var ErrHDC1008Timeout = errors.New("hdc1008: measurement did not complete")

// ErrHDC1008Resolution is returned by SetResolution for unsupported bit counts.
var ErrHDC1008Resolution = errors.New("hdc1008: temperature bits must be 14 or 11, humidity bits 14, 11 or 8")

func hdc1008Temperature(raw uint16) float32 { return float32(raw)/65536.0*165.0 - 40.0 }
func hdc1008Humidity(raw uint16) float32    { return float32(raw) / 65536.0 * 100.0 }

// HDC1008Minimal is the HDC1008 humidity and temperature sensor driver
// (Texas Instruments) — minimal interface.
//
// Init writes the configuration 0x1000 (14-bit T and RH, sequence mode, heater
// off). Read triggers one sequence measurement (pointer 0x00), polls the NACKed
// read header (timeout 50 ms) and converts the temperature and humidity words.
// The constructor does no bus access.
type HDC1008Minimal struct {
	conn    connection.Connection
	config  uint16
	pending []byte
}

// NewHDC1008Minimal creates the driver. conn must be a configured plain I²C
// connection bound to 0x40–0x43.
func NewHDC1008Minimal(conn connection.Connection) *HDC1008Minimal {
	return &HDC1008Minimal{conn: conn, config: hdc1008ConfigDefault}
}

func (d *HDC1008Minimal) writeConfig() error {
	return d.conn.Write([]byte{hdc1008RegConfig, byte(d.config >> 8), byte(d.config)})
}

// Init writes the default configuration 0x1000.
func (d *HDC1008Minimal) Init() error {
	d.config = hdc1008ConfigDefault
	d.pending = nil
	return d.writeConfig()
}

func (d *HDC1008Minimal) triggerRead(pointer byte, n int) ([]byte, error) {
	d.pending = nil
	if err := d.conn.Write([]byte{pointer}); err != nil {
		return nil, err
	}
	for i := 0; i < hdc1008PollAttempts; i++ {
		data, err := d.conn.Read(n)
		if err == nil {
			return data, nil
		}
		time.Sleep(time.Millisecond) // read header NACKed: not ready yet
	}
	return nil, ErrHDC1008Timeout
}

func (d *HDC1008Minimal) setMode(sequence bool) error {
	want := d.config &^ hdc1008CfgMode
	if sequence {
		want = d.config | hdc1008CfgMode
	}
	if want != d.config {
		d.config = want
		return d.writeConfig()
	}
	return nil
}

func (d *HDC1008Minimal) measurePair() (uint16, uint16, error) {
	if err := d.setMode(true); err != nil {
		return 0, 0, err
	}
	b, err := d.triggerRead(hdc1008RegTemperature, 4)
	if err != nil {
		return 0, 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), uint16(b[2])<<8 | uint16(b[3]), nil
}

// Read measures temperature and humidity (sequence mode, temperature first)
// and returns °C and %RH.
func (d *HDC1008Minimal) Read() (float32, float32, error) {
	t, h, err := d.measurePair()
	if err != nil {
		return 0, 0, err
	}
	return hdc1008Temperature(t), hdc1008Humidity(h), nil
}

// HDC1008Full is the full HDC1008 driver: single values, resolution, heater,
// battery flag, serial ID and the DRDYn data-ready interrupt. It extends
// HDC1008Minimal.
type HDC1008Full struct {
	*HDC1008Minimal

	mu          sync.Mutex
	callback    func(temperatureC, humidityPct float32)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewHDC1008Full creates the full driver; see NewHDC1008Minimal.
func NewHDC1008Full(conn connection.Connection) *HDC1008Full {
	return &HDC1008Full{HDC1008Minimal: NewHDC1008Minimal(conn)}
}

// ReadRaw measures and returns the raw 16-bit words (bits 1:0 zero).
func (d *HDC1008Full) ReadRaw() (uint16, uint16, error) { return d.measurePair() }

// ReadTemperature measures temperature only (single mode) in °C.
func (d *HDC1008Full) ReadTemperature() (float32, error) {
	if err := d.setMode(false); err != nil {
		return 0, err
	}
	b, err := d.triggerRead(hdc1008RegTemperature, 2)
	if err != nil {
		return 0, err
	}
	return hdc1008Temperature(uint16(b[0])<<8 | uint16(b[1])), nil
}

// ReadHumidity measures humidity only (single mode) in %RH.
func (d *HDC1008Full) ReadHumidity() (float32, error) {
	if err := d.setMode(false); err != nil {
		return 0, err
	}
	b, err := d.triggerRead(hdc1008RegHumidity, 2)
	if err != nil {
		return 0, err
	}
	return hdc1008Humidity(uint16(b[0])<<8 | uint16(b[1])), nil
}

// SetResolution selects the conversion resolutions: temperature 14 or 11 bit,
// humidity 14, 11 or 8 bit. ErrHDC1008Resolution for other values.
func (d *HDC1008Full) SetResolution(temperatureBits, humidityBits int) error {
	var t, h uint16
	switch temperatureBits {
	case 14:
	case 11:
		t = hdc1008CfgTRES
	default:
		return ErrHDC1008Resolution
	}
	switch humidityBits {
	case 14:
	case 11:
		h = 0x0100
	case 8:
		h = 0x0200
	default:
		return ErrHDC1008Resolution
	}
	d.config = d.config&^(hdc1008CfgTRES|hdc1008CfgHRES) | t | h
	return d.writeConfig()
}

// SetHeater enables or disables the heater.
func (d *HDC1008Full) SetHeater(on bool) error {
	if on {
		d.config |= hdc1008CfgHeat
	} else {
		d.config &^= hdc1008CfgHeat
	}
	return d.writeConfig()
}

// ReadConfig reads the 16-bit configuration register.
func (d *HDC1008Full) ReadConfig() (uint16, error) {
	if err := d.conn.Write([]byte{hdc1008RegConfig}); err != nil {
		return 0, err
	}
	b, err := d.conn.Read(2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

// IsHeaterOn reports whether the heater bit is set in the configuration register.
func (d *HDC1008Full) IsHeaterOn() (bool, error) {
	c, err := d.ReadConfig()
	return c&hdc1008CfgHeat != 0, err
}

// BatteryLow reports whether VDD is below 2.8 V (BTST).
func (d *HDC1008Full) BatteryLow() (bool, error) {
	c, err := d.ReadConfig()
	return c&hdc1008CfgBTST != 0, err
}

// SoftwareReset sets RST, waits 15 ms and rewrites the stored configuration.
func (d *HDC1008Full) SoftwareReset() error {
	if err := d.conn.Write([]byte{hdc1008RegConfig, byte(hdc1008CfgRST >> 8), 0x00}); err != nil {
		return err
	}
	time.Sleep(15 * time.Millisecond)
	return d.writeConfig()
}

// SerialID reads the 40-bit serial number from registers 0xFB–0xFD.
func (d *HDC1008Full) SerialID() (uint64, error) {
	var w [3]uint64
	for i := range w {
		if err := d.conn.Write([]byte{byte(hdc1008RegSerial1 + i)}); err != nil {
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
func (d *HDC1008Full) ConversionTime() float32 {
	t := float32(0.00635)
	if d.config&hdc1008CfgTRES != 0 {
		t = 0.00365
	}
	switch d.config & hdc1008CfgHRES {
	case 0x0100:
		return t + 0.00385
	case 0x0200:
		return t + 0.0025
	}
	return t + 0.0065
}

// PollInterrupt reports whether a triggered measurement is ready: an ACKed
// read header means ready (the bytes are read and cached for the callback), a
// NACK means not ready.
func (d *HDC1008Full) PollInterrupt() (bool, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.pending != nil {
		return true, nil
	}
	n := 2
	if d.config&hdc1008CfgMode != 0 {
		n = 4
	}
	b, err := d.conn.Read(n)
	if err != nil {
		return false, nil
	}
	d.pending = b
	return true, nil
}

// OnInterrupt subscribes callback to the data-ready event. With the
// connection's IntPin wired it fires on DRDYn falling edges, otherwise a 5 ms
// polling goroutine does. The caller triggers the measurement; the callback
// receives the converted result (humidity is NaN after a single-mode
// temperature trigger).
func (d *HDC1008Full) OnInterrupt(callback func(temperatureC, humidityPct float32)) error {
	_ = d.OffInterrupt()
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()
	pin := connection.InputPin(nil)
	if p := d.conn.IntPin(); p != nil {
		pin = p
	} else {
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		pin = poll
	}
	unsub := pin.OnEdge(connection.Falling, func() { d.handleEdge() })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *HDC1008Full) OffInterrupt() error {
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

func (d *HDC1008Full) handleEdge() {
	if ok, _ := d.PollInterrupt(); !ok {
		return
	}
	d.mu.Lock()
	b := d.pending
	d.pending = nil
	cb := d.callback
	d.mu.Unlock()
	if cb == nil || len(b) < 2 {
		return
	}
	t := hdc1008Temperature(uint16(b[0])<<8 | uint16(b[1]))
	h := float32(math.NaN())
	if len(b) >= 4 {
		h = hdc1008Humidity(uint16(b[2])<<8 | uint16(b[3]))
	}
	cb(t, h)
}

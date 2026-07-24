// Package humidity contains drivers for standalone humidity sensors.
package humidity

import (
	"fmt"

	"github.com/tuhde/Periph/go/periph/transport"
)

// DHT11Error is returned by DHT11 operations when the received frame's
// checksum is invalid or the frame has the wrong length.
type DHT11Error struct {
	Op   string
	Got  byte
	Exp  byte
	Size int
}

func (e *DHT11Error) Error() string {
	switch e.Op {
	case "frame_size":
		return fmt.Sprintf("dht11: frame must be 5 bytes, got %d", e.Size)
	case "checksum":
		return fmt.Sprintf("dht11: checksum mismatch: expected 0x%02X, got 0x%02X", e.Exp, e.Got)
	default:
		return fmt.Sprintf("dht11: %s", e.Op)
	}
}

// DHT11Minimal is the DHT11 combined temperature and humidity sensor driver
// — minimal interface.
//
// The DHT11 returns a 40-bit reading (humidity integer + decimal,
// temperature integer + decimal, checksum) over a single bidirectional
// data line. The driver accepts a `*DHTxxTransport` that handles the
// underlying single-wire protocol; this class is responsible only for
// validating the frame and converting it to engineering units.
//
// Default configuration (baked in at construction):
//   - Single read attempt; returns an error on checksum mismatch
//   - Caller responsible for respecting the ≥ 2 s sampling interval
type DHT11Minimal struct {
	transport *transport.DHTxxTransport
}

// NewDHT11Minimal creates a new DHT11Minimal bound to the given DHTxx
// transport. The transport must be a configured `*DHTxxTransport`
// bound to the chip's DATA pin.
func NewDHT11Minimal(t *transport.DHTxxTransport) (*DHT11Minimal, error) {
	return &DHT11Minimal{transport: t}, nil
}

// Read executes a full measurement cycle and returns temperature and
// humidity in a single transaction.
//
// Calls transport.Read() to obtain the 5-byte frame, validates the
// checksum, and decodes the values. Returns an error on checksum
// mismatch.
func (d *DHT11Minimal) Read() (temperatureC, humidityRH float32, err error) {
	frame, err := d.transport.Read()
	if err != nil {
		return 0, 0, err
	}
	t, h, err := decodeFrame(frame)
	if err != nil {
		return 0, 0, err
	}
	return t, h, nil
}

// IsReady reports whether the transport can attempt a read.
//
// This is a no-op on the chip driver side; the transport is always
// ready. Provided for API symmetry with HX711-style drivers.
func (d *DHT11Minimal) IsReady() bool {
	return true
}

// decodeFrame validates the 5-byte DHT11 frame and decodes the values
// into temperature in °C and humidity in %RH.
func decodeFrame(frame []byte) (temperatureC, humidityRH float32, err error) {
	if len(frame) != 5 {
		return 0, 0, &DHT11Error{Op: "frame_size", Size: len(frame)}
	}
	humInt, humDec, tempInt, tempDec, checksum := frame[0], frame[1], frame[2], frame[3], frame[4]
	expected := byte((uint16(humInt) + uint16(humDec) + uint16(tempInt) + uint16(tempDec)) & 0xFF)
	if expected != checksum {
		return 0, 0, &DHT11Error{Op: "checksum", Exp: expected, Got: checksum}
	}
	humidity := float32(humInt) + float32(humDec)/10.0
	sign := float32(1)
	if tempDec&0x80 != 0 {
		sign = -1
	}
	tempDecValue := tempDec & 0x7F
	temperature := sign * (float32(tempInt) + float32(tempDecValue)/10.0)
	return temperature, humidity, nil
}

// DHT11Full is the DHT11 combined temperature and humidity sensor driver
// — full interface. Extends DHT11Minimal with retry, raw access, and
// separate temperature/humidity accessors.
//
// Embeds DHT11Minimal to inherit Read and constructor; exposes
// DHT11Full-only methods below.
type DHT11Full struct {
	*DHT11Minimal
	maxRetries uint8
}

// NewDHT11Full creates a new DHT11Full bound to the given DHTxx transport.
// The default maxRetries is 3 — callers may override it per call via
// ReadRetry.
func NewDHT11Full(t *transport.DHTxxTransport) (*DHT11Full, error) {
	m, err := NewDHT11Minimal(t)
	if err != nil {
		return nil, err
	}
	return &DHT11Full{DHT11Minimal: m, maxRetries: 3}, nil
}

// ReadTemperature triggers a measurement and returns temperature only.
func (d *DHT11Full) ReadTemperature() (float32, error) {
	t, _, err := d.Read()
	return t, err
}

// ReadHumidity triggers a measurement and returns humidity only.
func (d *DHT11Full) ReadHumidity() (float32, error) {
	_, h, err := d.Read()
	return h, err
}

// ReadRetry reads both values, retrying on checksum error up to maxRetries
// times. If maxRetries is 0, the driver's default is used.
//
// Returns the last error if all attempts fail.
func (d *DHT11Full) ReadRetry(maxRetries uint8) (temperatureC, humidityRH float32, err error) {
	if maxRetries == 0 {
		maxRetries = d.maxRetries
	}
	var lastErr error
	for i := uint8(0); i < maxRetries; i++ {
		t, h, e := d.Read()
		if e == nil {
			return t, h, nil
		}
		lastErr = e
	}
	return 0, 0, fmt.Errorf("dht11: read_retry exhausted after %d attempts: %w", maxRetries, lastErr)
}

// ReadRaw reads the raw 5-byte frame and validates the checksum.
//
// The frame is returned as [hum_int, hum_dec, temp_int, temp_dec, checksum].
func (d *DHT11Full) ReadRaw() ([]byte, error) {
	frame, err := d.transport.Read()
	if err != nil {
		return nil, err
	}
	if len(frame) != 5 {
		return nil, &DHT11Error{Op: "frame_size", Size: len(frame)}
	}
	humInt, humDec, tempInt, tempDec, checksum := frame[0], frame[1], frame[2], frame[3], frame[4]
	expected := byte((uint16(humInt) + uint16(humDec) + uint16(tempInt) + uint16(tempDec)) & 0xFF)
	if expected != checksum {
		return nil, &DHT11Error{Op: "checksum", Exp: expected, Got: checksum}
	}
	return frame, nil
}

// Package adcdac contains drivers for ADC and DAC converter chips.
package adcdac

import (
	"fmt"

	"github.com/tuhde/Periph/go/periph/transport"
)

// HX711 gain values accepted by SetGain.
//
// HX711Gain128 and HX711Gain64 select Channel A; HX711Gain32 selects
// Channel B (fixed gain).
const (
	HX711Gain128 uint8 = 128
	HX711Gain64  uint8 = 64
	HX711Gain32  uint8 = 32
)

// HX711 pulses-per-conversion corresponding to each gain. The HX711
// implicitly programs the next conversion's channel and gain via the
// number of extra clock pulses issued past the 24 data bits.
const (
	hx711Pulses128 uint8 = 25
	hx711Pulses64  uint8 = 27
	hx711Pulses32  uint8 = 26
)

// HX711Error is returned by HX711 operations.
type HX711Error struct {
	Op  string
	Got uint8
}

func (e *HX711Error) Error() string {
	switch e.Op {
	case "invalid_gain":
		return fmt.Sprintf("hx711: invalid gain %d (must be 128, 64, or 32)", e.Got)
	default:
		return fmt.Sprintf("hx711: %s", e.Op)
	}
}

// HX711Minimal is the HX711 24-bit ADC driver — minimal interface.
//
// Reads signed 24-bit ADC values from Channel A at Gain 128. The
// first post-power-up conversion is discarded during construction.
//
// The driver accepts a `*HX711Transport` that handles the underlying
// 2-wire bit-bang protocol; this class adds gain selection, multi-sample
// averaging, tare offset capture, scale factor calibration, and power
// management on top of the Minimal API.
type HX711Minimal struct {
	transport *transport.HX711Transport
}

// NewHX711Minimal creates a new HX711Minimal bound to the given HX711
// transport. Discards the first post-power-up conversion so the next
// read returns a valid result.
func NewHX711Minimal(t *transport.HX711Transport) (*HX711Minimal, error) {
	d := &HX711Minimal{transport: t}
	if _, err := d.transport.ReadRaw(int(hx711Pulses128)); err != nil {
		return nil, err
	}
	return d, nil
}

// IsReady returns true if DOUT is LOW (a conversion result is available).
//
// Non-blocking.
func (d *HX711Minimal) IsReady() (bool, error) {
	return d.transport.IsReady()
}

// ReadRaw blocks until data is ready and returns a signed 24-bit ADC
// value from Channel A at Gain 128.
func (d *HX711Minimal) ReadRaw() (int32, error) {
	return d.transport.ReadRaw(int(hx711Pulses128))
}

// HX711Full is the HX711 24-bit ADC driver — full interface. Extends
// HX711Minimal with gain, multi-sample averaging, tare offset, scale
// factor, and power management.
//
// Embeds HX711Minimal to inherit IsReady and the constructor; exposes
// HX711Full-only methods below.
type HX711Full struct {
	*HX711Minimal
	pulses uint8
	offset int32
	scale  float32
}

// NewHX711Full creates a new HX711Full with default gain 128, offset 0,
// and scale 1.0. Discards the first post-power-up conversion.
func NewHX711Full(t *transport.HX711Transport) (*HX711Full, error) {
	m, err := NewHX711Minimal(t)
	if err != nil {
		return nil, err
	}
	return &HX711Full{
		HX711Minimal: m,
		pulses:       hx711Pulses128,
		offset:       0,
		scale:        1.0,
	}, nil
}

// ReadRaw blocks until data is ready and returns a signed 24-bit ADC
// value using the currently selected channel and gain.
func (d *HX711Full) ReadRaw() (int32, error) {
	return d.transport.ReadRaw(int(d.pulses))
}

// SetGain selects the input channel and gain.
//
// Gain 128 and 64 use Channel A; gain 32 uses Channel B. Issues one
// dummy read to apply the new gain before returning.
func (d *HX711Full) SetGain(gain uint8) error {
	switch gain {
	case HX711Gain128:
		d.pulses = hx711Pulses128
	case HX711Gain64:
		d.pulses = hx711Pulses64
	case HX711Gain32:
		d.pulses = hx711Pulses32
	default:
		return &HX711Error{Op: "invalid_gain", Got: gain}
	}
	if _, err := d.transport.ReadRaw(int(d.pulses)); err != nil {
		return err
	}
	return nil
}

// ReadAverage returns the average of `times` raw ADC readings.
func (d *HX711Full) ReadAverage(times uint8) (int32, error) {
	if times == 0 {
		return 0, fmt.Errorf("hx711: times must be > 0")
	}
	var total int64
	for i := uint8(0); i < times; i++ {
		v, err := d.ReadRaw()
		if err != nil {
			return 0, err
		}
		total += int64(v)
	}
	return int32(total / int64(times)), nil
}

// Tare captures the current average reading as the zero offset.
func (d *HX711Full) Tare(times uint8) error {
	avg, err := d.ReadAverage(times)
	if err != nil {
		return err
	}
	d.offset = avg
	return nil
}

// GetOffset returns the current tare offset.
func (d *HX711Full) GetOffset() int32 {
	return d.offset
}

// SetScale sets the calibration scale factor.
//
// Calibrate with a known weight: `factor = (read_average() - offset) / known_weight`.
func (d *HX711Full) SetScale(factor float32) {
	d.scale = factor
}

// GetScale returns the current scale factor.
func (d *HX711Full) GetScale() float32 {
	return d.scale
}

// ReadWeight returns the calibrated weight in the units defined by the
// scale factor. Computes `(read_average(times) - offset) / scale`.
func (d *HX711Full) ReadWeight(times uint8) (float32, error) {
	avg, err := d.ReadAverage(times)
	if err != nil {
		return 0, err
	}
	return float32(avg-d.offset) / d.scale, nil
}

// PowerDown holds PD_SCK HIGH for >60 µs to put the chip into
// power-down mode.
func (d *HX711Full) PowerDown() error {
	return d.transport.PowerDown()
}

// PowerUp drives PD_SCK LOW to reset the chip to Channel A, Gain 128
// and discards the first post-reset conversion.
func (d *HX711Full) PowerUp() error {
	if err := d.transport.PowerUp(); err != nil {
		return err
	}
	d.pulses = hx711Pulses128
	if _, err := d.transport.ReadRaw(int(hx711Pulses128)); err != nil {
		return err
	}
	return nil
}

// Package adcdac contains drivers for ADC and DAC chips.
package adcdac

import (
	"fmt"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HX710B output rate values accepted by SetRate.
//
// The HX710B has a single, fixed-gain (128) differential input — unlike
// the HX711, there is no channel or gain selection, only the output rate.
const (
	HX710BRate10SPS uint8 = 10
	HX710BRate40SPS uint8 = 40
)

// HX710B pulses-per-conversion. The HX710B implicitly programs the next
// conversion's reading type and rate via the number of extra clock pulses
// issued past the 24 data bits: 25 → differential input at 10 Hz, 26 →
// DVDD−AVDD supply-difference at 40 Hz, 27 → differential input at 40 Hz.
const (
	hx710bPulses10SPS     uint8 = 25
	hx710bPulsesSupplyDiff uint8 = 26
	hx710bPulses40SPS     uint8 = 27
)

// HX710BError is returned by HX710B operations.
type HX710BError struct {
	Op  string
	Got uint8
}

func (e *HX710BError) Error() string {
	switch e.Op {
	case "invalid_rate":
		return fmt.Sprintf("hx710b: invalid rate %d (must be 10 or 40)", e.Got)
	default:
		return fmt.Sprintf("hx710b: %s", e.Op)
	}
}

// HX710BMinimal is the HX710B 24-bit ADC driver — minimal interface.
//
// Reads signed 24-bit ADC values from the single differential input
// (INN/INP) at Gain 128, 10 SPS. The first post-power-up conversion is
// discarded during construction.
//
// The driver accepts a `connection.HX711Conn` that handles the underlying
// 2-wire bit-bang protocol (the HX710B reuses the HX711 transport).
type HX710BMinimal struct {
	connection connection.HX711Conn
}

// NewHX710BMinimal creates a new HX710BMinimal bound to the given HX711
// connection. Discards the first post-power-up conversion so the next
// read returns a valid result.
func NewHX710BMinimal(t connection.HX711Conn) (*HX710BMinimal, error) {
	d := &HX710BMinimal{connection: t}
	if _, err := d.connection.ReadRaw(int(hx710bPulses10SPS)); err != nil {
		return nil, err
	}
	return d, nil
}

// IsReady returns true if DOUT is LOW (a conversion result is available).
//
// Non-blocking.
func (d *HX710BMinimal) IsReady() (bool, error) {
	return d.connection.IsReady()
}

// ReadRaw blocks until data is ready and returns a signed 24-bit ADC
// value from the differential input at Gain 128, 10 SPS.
func (d *HX710BMinimal) ReadRaw() (int32, error) {
	return d.connection.ReadRaw(int(hx710bPulses10SPS))
}

// HX710BFull is the HX710B 24-bit ADC driver — full interface. Extends
// HX710BMinimal with output rate selection, multi-sample averaging, tare
// offset, scale factor, supply-difference monitoring, and power management.
//
// Embeds HX710BMinimal to inherit IsReady and the constructor; exposes
// HX710BFull-only methods below.
type HX710BFull struct {
	*HX710BMinimal
	pulses uint8
	offset int32
	scale  float32
}

// NewHX710BFull creates a new HX710BFull with default 10 SPS rate, offset
// 0, and scale 1.0. Discards the first post-power-up conversion.
func NewHX710BFull(t connection.HX711Conn) (*HX710BFull, error) {
	m, err := NewHX710BMinimal(t)
	if err != nil {
		return nil, err
	}
	return &HX710BFull{
		HX710BMinimal: m,
		pulses:        hx710bPulses10SPS,
		offset:        0,
		scale:         1.0,
	}, nil
}

// ReadRaw blocks until data is ready and returns a signed 24-bit ADC
// value using the currently selected output rate.
func (d *HX710BFull) ReadRaw() (int32, error) {
	return d.connection.ReadRaw(int(d.pulses))
}

// SetRate selects the differential-input output rate.
//
// 10 or 40 samples per second. Issues one dummy read to apply the new
// rate before returning.
func (d *HX710BFull) SetRate(rate uint8) error {
	switch rate {
	case HX710BRate10SPS:
		d.pulses = hx710bPulses10SPS
	case HX710BRate40SPS:
		d.pulses = hx710bPulses40SPS
	default:
		return &HX710BError{Op: "invalid_rate", Got: rate}
	}
	if _, err := d.connection.ReadRaw(int(d.pulses)); err != nil {
		return err
	}
	return nil
}

// ReadAverage returns the average of `times` raw differential-input readings.
func (d *HX710BFull) ReadAverage(times uint8) (int32, error) {
	if times == 0 {
		return 0, fmt.Errorf("hx710b: times must be > 0")
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
func (d *HX710BFull) Tare(times uint8) error {
	avg, err := d.ReadAverage(times)
	if err != nil {
		return err
	}
	d.offset = avg
	return nil
}

// GetOffset returns the current tare offset.
func (d *HX710BFull) GetOffset() int32 {
	return d.offset
}

// SetScale sets the calibration scale factor.
//
// Calibrate with a known weight: `factor = (read_average() - offset) / known_weight`.
func (d *HX710BFull) SetScale(factor float32) {
	d.scale = factor
}

// GetScale returns the current scale factor.
func (d *HX710BFull) GetScale() float32 {
	return d.scale
}

// ReadWeight returns the calibrated weight in the units defined by the
// scale factor. Computes `(read_average(times) - offset) / scale`.
func (d *HX710BFull) ReadWeight(times uint8) (float32, error) {
	avg, err := d.ReadAverage(times)
	if err != nil {
		return 0, err
	}
	return float32(avg-d.offset) / d.scale, nil
}

// ReadSupplyDiffRaw blocks until data is ready and returns the raw
// DVDD−AVDD supply-difference code (26 pulses, 40 Hz). This is not a
// calibrated volts value — the datasheet gives no LSB-to-volts scale
// factor for this reading; it is described only qualitatively as usable
// for battery-voltage detection. Use for relative drift tracking against
// a known-good baseline, or calibrate against a reference voltmeter for
// absolute readings.
func (d *HX710BFull) ReadSupplyDiffRaw() (int32, error) {
	return d.connection.ReadRaw(int(hx710bPulsesSupplyDiff))
}

// PowerDown holds PD_SCK HIGH for >60 µs to put the chip into
// power-down mode.
func (d *HX710BFull) PowerDown() error {
	return d.connection.PowerDown()
}

// PowerUp drives PD_SCK LOW to reset the chip to the differential input
// at Gain 128, 10 SPS and discards the first post-reset conversion.
func (d *HX710BFull) PowerUp() error {
	if err := d.connection.PowerUp(); err != nil {
		return err
	}
	d.pulses = hx710bPulses10SPS
	if _, err := d.connection.ReadRaw(int(hx710bPulses10SPS)); err != nil {
		return err
	}
	return nil
}

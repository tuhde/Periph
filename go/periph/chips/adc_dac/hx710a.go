package adcdac

import (
	"fmt"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HX710A output rate values accepted by SetRate.
//
// The HX710A has a single, fixed-gain (128) differential input — unlike
// the HX711, there is no channel or gain selection, only the output rate.
const (
	HX710ARate10SPS uint8 = 10
	HX710ARate40SPS uint8 = 40
)

// HX710A pulses-per-conversion. The HX710A implicitly programs the next
// conversion's reading type and rate via the number of extra clock pulses
// issued past the 24 data bits: 25 → differential input at 10 Hz, 26 →
// temperature at 40 Hz, 27 → differential input at 40 Hz.
const (
	hx710aPulses10SPS uint8 = 25
	hx710aPulsesTemp  uint8 = 26
	hx710aPulses40SPS uint8 = 27
)

// HX710AError is returned by HX710A operations.
type HX710AError struct {
	Op  string
	Got uint8
}

func (e *HX710AError) Error() string {
	switch e.Op {
	case "invalid_rate":
		return fmt.Sprintf("hx710a: invalid rate %d (must be 10 or 40)", e.Got)
	default:
		return fmt.Sprintf("hx710a: %s", e.Op)
	}
}

// HX710AMinimal is the HX710A 24-bit ADC driver — minimal interface.
//
// Reads signed 24-bit ADC values from the single differential input
// (INN/INP) at Gain 128, 10 SPS. The first post-power-up conversion is
// discarded during construction.
//
// The driver accepts a `*HX711Connection` that handles the underlying
// 2-wire bit-bang protocol (the HX710A reuses the HX711 transport).
type HX710AMinimal struct {
	connection connection.HX711Conn
}

// NewHX710AMinimal creates a new HX710AMinimal bound to the given HX711
// connection. Discards the first post-power-up conversion so the next
// read returns a valid result.
func NewHX710AMinimal(t connection.HX711Conn) (*HX710AMinimal, error) {
	d := &HX710AMinimal{connection: t}
	if _, err := d.connection.ReadRaw(int(hx710aPulses10SPS)); err != nil {
		return nil, err
	}
	return d, nil
}

// IsReady returns true if DOUT is LOW (a conversion result is available).
//
// Non-blocking.
func (d *HX710AMinimal) IsReady() (bool, error) {
	return d.connection.IsReady()
}

// ReadRaw blocks until data is ready and returns a signed 24-bit ADC
// value from the differential input at Gain 128, 10 SPS.
func (d *HX710AMinimal) ReadRaw() (int32, error) {
	return d.connection.ReadRaw(int(hx710aPulses10SPS))
}

// HX710AFull is the HX710A 24-bit ADC driver — full interface. Extends
// HX710AMinimal with output rate selection, multi-sample averaging, tare
// offset, scale factor, temperature, and power management.
//
// Embeds HX710AMinimal to inherit IsReady and the constructor; exposes
// HX710AFull-only methods below.
type HX710AFull struct {
	*HX710AMinimal
	pulses uint8
	offset int32
	scale  float32
}

// NewHX710AFull creates a new HX710AFull with default 10 SPS rate, offset
// 0, and scale 1.0. Discards the first post-power-up conversion.
func NewHX710AFull(t connection.HX711Conn) (*HX710AFull, error) {
	m, err := NewHX710AMinimal(t)
	if err != nil {
		return nil, err
	}
	return &HX710AFull{
		HX710AMinimal: m,
		pulses:        hx710aPulses10SPS,
		offset:        0,
		scale:         1.0,
	}, nil
}

// ReadRaw blocks until data is ready and returns a signed 24-bit ADC
// value using the currently selected output rate.
func (d *HX710AFull) ReadRaw() (int32, error) {
	return d.connection.ReadRaw(int(d.pulses))
}

// SetRate selects the differential-input output rate.
//
// 10 or 40 samples per second. Issues one dummy read to apply the new
// rate before returning.
func (d *HX710AFull) SetRate(rate uint8) error {
	switch rate {
	case HX710ARate10SPS:
		d.pulses = hx710aPulses10SPS
	case HX710ARate40SPS:
		d.pulses = hx710aPulses40SPS
	default:
		return &HX710AError{Op: "invalid_rate", Got: rate}
	}
	if _, err := d.connection.ReadRaw(int(d.pulses)); err != nil {
		return err
	}
	return nil
}

// ReadAverage returns the average of `times` raw differential-input readings.
func (d *HX710AFull) ReadAverage(times uint8) (int32, error) {
	if times == 0 {
		return 0, fmt.Errorf("hx710a: times must be > 0")
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
func (d *HX710AFull) Tare(times uint8) error {
	avg, err := d.ReadAverage(times)
	if err != nil {
		return err
	}
	d.offset = avg
	return nil
}

// GetOffset returns the current tare offset.
func (d *HX710AFull) GetOffset() int32 {
	return d.offset
}

// SetScale sets the calibration scale factor.
//
// Calibrate with a known weight: `factor = (read_average() - offset) / known_weight`.
func (d *HX710AFull) SetScale(factor float32) {
	d.scale = factor
}

// GetScale returns the current scale factor.
func (d *HX710AFull) GetScale() float32 {
	return d.scale
}

// ReadWeight returns the calibrated weight in the units defined by the
// scale factor. Computes `(read_average(times) - offset) / scale`.
func (d *HX710AFull) ReadWeight(times uint8) (float32, error) {
	avg, err := d.ReadAverage(times)
	if err != nil {
		return 0, err
	}
	return float32(avg-d.offset) / d.scale, nil
}

// ReadTemperatureRaw blocks until data is ready and returns the raw
// on-chip temperature code (26 pulses, 40 Hz). This is not a calibrated
// degrees-Celsius value — the datasheet gives only a typical resolution
// of ~20.4 LSB/°C and states offset and gain vary significantly
// chip-to-chip. Use for relative drift tracking, or calibrate against a
// reference thermometer for absolute readings.
func (d *HX710AFull) ReadTemperatureRaw() (int32, error) {
	return d.connection.ReadRaw(int(hx710aPulsesTemp))
}

// PowerDown holds PD_SCK HIGH for >60 µs to put the chip into
// power-down mode.
func (d *HX710AFull) PowerDown() error {
	return d.connection.PowerDown()
}

// PowerUp drives PD_SCK LOW to reset the chip to the differential input
// at Gain 128, 10 SPS and discards the first post-reset conversion.
func (d *HX710AFull) PowerUp() error {
	if err := d.connection.PowerUp(); err != nil {
		return err
	}
	d.pulses = hx710aPulses10SPS
	if _, err := d.connection.ReadRaw(int(hx710aPulses10SPS)); err != nil {
		return err
	}
	return nil
}

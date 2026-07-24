// Package led contains drivers for LED strips and LED drivers.
package led

import (
	"github.com/tuhde/Periph/go/periph/transport"
)

// MaxPixelsSK6812RGBW is the maximum supported pixel count for the
// internal GRBW buffer. Pixel indices beyond this limit are clamped
// silently.
const MaxPixelsSK6812RGBW = 256

// sk6812RgbwResetBytes is the number of trailing zero bytes appended
// to every transmission to guarantee the SK6812RGBW's ≥80 µs reset
// pulse. The transport already appends 16 zero bytes (~53 µs); we add
// 24 more (~80 µs at 2.4 MHz SPI encoding) so the total reset exceeds
// the chip's 80 µs minimum.
const sk6812RgbwResetBytes = 24

// SK6812RGBWMinimal is the SK6812RGBW addressable RGBW LED strip driver
// — minimal interface.
//
// Wraps a `*NeoPixelTransport` and maintains an internal GRBW pixel
// buffer. `Fill` updates every pixel and transmits immediately;
// `Off` is shorthand for `Fill(0, 0, 0, 0)`.
type SK6812RGBWMinimal struct {
	transport *transport.NeoPixelTransport
	n         int
	buf       []byte
}

// NewSK6812RGBWMinimal creates a new SK6812RGBWMinimal bound to the
// given NeoPixel transport and pixel count. The pixel count is
// clamped to `MaxPixelsSK6812RGBW`.
func NewSK6812RGBWMinimal(t *transport.NeoPixelTransport, n int) (*SK6812RGBWMinimal, error) {
	if n > MaxPixelsSK6812RGBW {
		n = MaxPixelsSK6812RGBW
	}
	if n < 0 {
		n = 0
	}
	return &SK6812RGBWMinimal{
		transport: t,
		n:         n,
		buf:       make([]byte, n*4),
	}, nil
}

// Fill fills every pixel with one RGBW colour and sends to the strip
// immediately. Each channel is clamped to [0, 255]. Stores G, R, B, W
// in the internal buffer (GRBW wire order) then calls
// `transport.Write` with trailing reset bytes for the chip's ≥80 µs
// reset pulse.
func (d *SK6812RGBWMinimal) Fill(r, g, b, w uint8) error {
	for i := 0; i < d.n; i++ {
		d.buf[i*4+0] = g
		d.buf[i*4+1] = r
		d.buf[i*4+2] = b
		d.buf[i*4+3] = w
	}
	payload := make([]byte, d.n*4+sk6812RgbwResetBytes)
	copy(payload, d.buf[:d.n*4])
	return d.transport.Write(payload)
}

// Off turns off all pixels (fill with all zeros and send).
func (d *SK6812RGBWMinimal) Off() error {
	return d.Fill(0, 0, 0, 0)
}

// SK6812RGBWFull is the SK6812RGBW addressable RGBW LED strip driver
// — full interface. Extends SK6812RGBWMinimal with per-pixel addressing,
// explicit `Show`, global brightness scaling, buffer rotation, and
// HSV fill.
//
// Embeds SK6812RGBWMinimal to inherit Fill, Off, and the constructor;
// exposes SK6812RGBWFull-only methods below.
type SK6812RGBWFull struct {
	*SK6812RGBWMinimal
	brightness uint8
}

// NewSK6812RGBWFull creates a new SK6812RGBWFull with default brightness 255.
func NewSK6812RGBWFull(t *transport.NeoPixelTransport, n int) (*SK6812RGBWFull, error) {
	m, err := NewSK6812RGBWMinimal(t, n)
	if err != nil {
		return nil, err
	}
	return &SK6812RGBWFull{
		SK6812RGBWMinimal: m,
		brightness:        255,
	}, nil
}

// SetPixel writes one pixel into the buffer without sending. Index is
// clamped to [0, n-1]. Call `Show` to transmit.
func (d *SK6812RGBWFull) SetPixel(index int, r, g, b, w uint8) {
	if d.n == 0 {
		return
	}
	if index < 0 {
		index = 0
	}
	if index >= d.n {
		index = d.n - 1
	}
	d.buf[index*4+0] = g
	d.buf[index*4+1] = r
	d.buf[index*4+2] = b
	d.buf[index*4+3] = w
}

// Show transmits the current buffer to the strip, applying brightness
// scaling. Each channel is scaled: `sent = stored * brightness / 255`.
// Appends 24 trailing zero bytes to guarantee the chip's ≥80 µs
// reset pulse.
func (d *SK6812RGBWFull) Show() error {
	scaled := make([]byte, d.n*4)
	if d.brightness == 255 {
		copy(scaled, d.buf[:d.n*4])
	} else {
		for i := 0; i < d.n*4; i++ {
			scaled[i] = uint8(uint16(d.buf[i]) * uint16(d.brightness) / 255)
		}
	}
	payload := make([]byte, d.n*4+sk6812RgbwResetBytes)
	copy(payload, scaled)
	return d.transport.Write(payload)
}

// GetBrightness returns the global brightness scalar (0–255).
func (d *SK6812RGBWFull) GetBrightness() uint8 {
	return d.brightness
}

// SetBrightness sets the global brightness scalar (0–255). Applied
// non-destructively at `Show` time.
func (d *SK6812RGBWFull) SetBrightness(value uint8) {
	d.brightness = value
}

// Rotate shifts the pixel buffer left by `steps` positions in whole
// pixels (wraps around). Does not transmit — call `Show` afterwards.
func (d *SK6812RGBWFull) Rotate(steps int) {
	if d.n == 0 {
		return
	}
	s := steps % d.n
	if s < 0 {
		s += d.n
	}
	if s == 0 {
		return
	}
	bytes_ := s * 4
	n4 := d.n * 4
	tmp := make([]byte, bytes_)
	copy(tmp, d.buf[:bytes_])
	copy(d.buf, d.buf[bytes_:n4])
	copy(d.buf[n4-bytes_:n4], tmp)
}

// FillHSV converts HSV (all inputs 0.0–1.0) to RGB (w=0) and calls Fill.
func (d *SK6812RGBWFull) FillHSV(h, s, v float32) error {
	r, g, b := hsvToRGB(h, s, v)
	return d.Fill(r, g, b, 0)
}

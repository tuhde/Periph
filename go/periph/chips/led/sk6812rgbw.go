// Package led contains drivers for LED strips and LED drivers.
package led

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// MaxPixelsSK6812RGBW is the maximum supported pixel count for the
// internal GRBW buffer. Pixel indices beyond this limit are clamped
// silently.
const MaxPixelsSK6812RGBW = 256

// sk6812RgbwResetBytes is the reset-pulse length (in trailing zero bytes,
// post bit-encoding) requested via connection.ResetExtender to guarantee
// the SK6812RGBW's ≥80 µs reset pulse - longer than a plain Write's
// default ~53 µs (correct for WS2812B, too short for this chip). Padding
// the *pre-encoded* pixel buffer with extra zero bytes instead would not
// achieve this: those bytes get bit-encoded as more zero-value data bits
// (periodic low-with-brief-highs), not a continuous low - see
// connection.ResetExtender's doc comment.
const sk6812RgbwResetBytes = 24

// write sends data with the extended reset pulse SK6812RGBW needs, if the
// connection supports requesting one (connection.ResetExtender - Linux's
// NeoPixelConnection does; TinyGo's ws2812-backed one does not, since that
// board-native driver has no equivalent hook, so this falls back to a
// plain Write there).
func sk6812rgbwWrite(conn connection.Connection, data []byte) error {
	if re, ok := conn.(connection.ResetExtender); ok {
		return re.WriteExt(data, sk6812RgbwResetBytes)
	}
	return conn.Write(data)
}

// SK6812RGBWMinimal is the SK6812RGBW addressable RGBW LED strip driver
// — minimal interface.
//
// Wraps a `*NeoPixelConnection` and maintains an internal GRBW pixel
// buffer. `Fill` updates every pixel and transmits immediately;
// `Off` is shorthand for `Fill(0, 0, 0, 0)`.
type SK6812RGBWMinimal struct {
	connection connection.Connection
	n          int
	buf        []byte
}

// NewSK6812RGBWMinimal creates a new SK6812RGBWMinimal bound to the
// given NeoPixel connection and pixel count. The pixel count is
// clamped to `MaxPixelsSK6812RGBW`.
func NewSK6812RGBWMinimal(t connection.Connection, n int) (*SK6812RGBWMinimal, error) {
	if n > MaxPixelsSK6812RGBW {
		n = MaxPixelsSK6812RGBW
	}
	if n < 0 {
		n = 0
	}
	return &SK6812RGBWMinimal{
		connection: t,
		n:          n,
		buf:        make([]byte, n*4),
	}, nil
}

// Fill fills every pixel with one RGBW colour and sends to the strip
// immediately. Each channel is clamped to [0, 255]. Stores G, R, B, W
// in the internal buffer (GRBW wire order) then sends it via
// sk6812rgbwWrite, requesting the chip's ≥80 µs reset pulse where the
// connection supports it.
func (d *SK6812RGBWMinimal) Fill(r, g, b, w uint8) error {
	for i := 0; i < d.n; i++ {
		d.buf[i*4+0] = g
		d.buf[i*4+1] = r
		d.buf[i*4+2] = b
		d.buf[i*4+3] = w
	}
	return sk6812rgbwWrite(d.connection, d.buf[:d.n*4])
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
func NewSK6812RGBWFull(t connection.Connection, n int) (*SK6812RGBWFull, error) {
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
// Sent via sk6812rgbwWrite, requesting the chip's ≥80 µs reset pulse
// where the connection supports it.
func (d *SK6812RGBWFull) Show() error {
	scaled := make([]byte, d.n*4)
	if d.brightness == 255 {
		copy(scaled, d.buf[:d.n*4])
	} else {
		for i := 0; i < d.n*4; i++ {
			scaled[i] = uint8(uint16(d.buf[i]) * uint16(d.brightness) / 255)
		}
	}
	return sk6812rgbwWrite(d.connection, scaled)
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

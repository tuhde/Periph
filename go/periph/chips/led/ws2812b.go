// Package led contains drivers for LED strips and LED drivers.
package led

import (
	"github.com/tuhde/Periph/go/periph/transport"
)

// MaxPixelsWS2812B is the maximum supported pixel count for the
// internal GRB buffer. Pixel indices beyond this limit are clamped
// silently.
const MaxPixelsWS2812B = 256

// WS2812BMinimal is the WS2812B addressable RGB LED strip driver —
// minimal interface.
//
// Wraps a `*NeoPixelTransport` and maintains an internal GRB pixel
// buffer. `Fill` updates every pixel and transmits immediately;
// `Off` is shorthand for `Fill(0, 0, 0)`.
type WS2812BMinimal struct {
	transport *transport.NeoPixelTransport
	n         int
	buf       []byte
}

// NewWS2812BMinimal creates a new WS2812BMinimal bound to the given
// NeoPixel transport and pixel count. The pixel count is clamped to
// `MaxPixelsWS2812B`.
func NewWS2812BMinimal(t *transport.NeoPixelTransport, n int) (*WS2812BMinimal, error) {
	if n > MaxPixelsWS2812B {
		n = MaxPixelsWS2812B
	}
	if n < 0 {
		n = 0
	}
	return &WS2812BMinimal{
		transport: t,
		n:         n,
		buf:       make([]byte, n*3),
	}, nil
}

// Fill fills every pixel with one colour and sends to the strip
// immediately. Each channel is clamped to [0, 255]. Stores G, R, B
// in the internal buffer (GRB wire order) then calls
// `transport.Write`.
func (d *WS2812BMinimal) Fill(r, g, b uint8) error {
	for i := 0; i < d.n; i++ {
		d.buf[i*3+0] = g
		d.buf[i*3+1] = r
		d.buf[i*3+2] = b
	}
	return d.transport.Write(d.buf[:d.n*3])
}

// Off turns off all pixels (fill with black and send).
func (d *WS2812BMinimal) Off() error {
	return d.Fill(0, 0, 0)
}

// WS2812BFull is the WS2812B addressable RGB LED strip driver — full
// interface. Extends WS2812BMinimal with per-pixel addressing, explicit
// `Show`, global brightness scaling, buffer rotation, and HSV fill.
//
// Embeds WS2812BMinimal to inherit Fill, Off, and the constructor;
// exposes WS2812BFull-only methods below.
type WS2812BFull struct {
	*WS2812BMinimal
	brightness uint8
}

// NewWS2812BFull creates a new WS2812BFull with default brightness 255.
func NewWS2812BFull(t *transport.NeoPixelTransport, n int) (*WS2812BFull, error) {
	m, err := NewWS2812BMinimal(t, n)
	if err != nil {
		return nil, err
	}
	return &WS2812BFull{
		WS2812BMinimal: m,
		brightness:     255,
	}, nil
}

// SetPixel writes one pixel into the buffer without sending. Index is
// clamped to [0, n-1]. Call `Show` to transmit.
func (d *WS2812BFull) SetPixel(index int, r, g, b uint8) {
	if d.n == 0 {
		return
	}
	if index < 0 {
		index = 0
	}
	if index >= d.n {
		index = d.n - 1
	}
	d.buf[index*3+0] = g
	d.buf[index*3+1] = r
	d.buf[index*3+2] = b
}

// Show transmits the current buffer to the strip, applying brightness
// scaling. Each channel is scaled: `sent = stored * brightness / 255`.
func (d *WS2812BFull) Show() error {
	if d.brightness == 255 {
		return d.transport.Write(d.buf[:d.n*3])
	}
	scaled := make([]byte, d.n*3)
	for i := 0; i < d.n*3; i++ {
		scaled[i] = uint8(uint16(d.buf[i]) * uint16(d.brightness) / 255)
	}
	return d.transport.Write(scaled)
}

// GetBrightness returns the global brightness scalar (0–255).
func (d *WS2812BFull) GetBrightness() uint8 {
	return d.brightness
}

// SetBrightness sets the global brightness scalar (0–255). Applied
// non-destructively at `Show` time.
func (d *WS2812BFull) SetBrightness(value uint8) {
	d.brightness = value
}

// Rotate shifts the pixel buffer left by `steps` positions (wraps
// around). Does not transmit — call `Show` afterwards.
func (d *WS2812BFull) Rotate(steps int) {
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
	bytes_ := s * 3
	n3 := d.n * 3
	tmp := make([]byte, bytes_)
	copy(tmp, d.buf[:bytes_])
	copy(d.buf, d.buf[bytes_:n3])
	copy(d.buf[n3-bytes_:n3], tmp)
}

// FillHSV converts HSV (all inputs 0.0–1.0) to RGB and calls Fill.
func (d *WS2812BFull) FillHSV(h, s, v float32) error {
	r, g, b := hsvToRGB(h, s, v)
	return d.Fill(r, g, b)
}

// hsvToRGB converts an HSV colour (each channel 0.0–1.0) to RGB bytes
// (each channel 0–255). Uses the standard sector-based algorithm.
func hsvToRGB(h, s, v float32) (r, g, b uint8) {
	if s == 0.0 {
		c := uint8(v * 255.0)
		return c, c, c
	}
	h6 := h * 6.0
	if h6 < 0 {
		h6 = 0
	}
	if h6 >= 6.0 {
		h6 = 0
	}
	i := int(h6)
	f := h6 - float32(i)
	p := v * (1.0 - s)
	q := v * (1.0 - s*f)
	t := v * (1.0 - s*(1.0-f))
	vv := uint8(v * 255.0)
	pu := uint8(p * 255.0)
	qu := uint8(q * 255.0)
	tu := uint8(t * 255.0)
	switch i % 6 {
	case 0:
		return vv, tu, pu
	case 1:
		return qu, vv, pu
	case 2:
		return pu, vv, tu
	case 3:
		return pu, qu, vv
	case 4:
		return tu, pu, vv
	default:
		return vv, pu, qu
	}
}

// Package led contains drivers for LED strips and LED drivers.
package led

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// MaxPixelsNeoPixelRGBW is the maximum supported pixel count for the
// internal RGBW buffer shared by 4-channel NeoPixel-protocol chips.
// Pixel indices beyond this limit are clamped silently.
const MaxPixelsNeoPixelRGBW = 256

// neoPixelRGBWWrite sends data with the reset pulse length requested,
// using connection.ResetExtender where the connection supports it
// (Linux's NeoPixelConnection does; TinyGo's board-native ws2812-backed
// one does not, so this falls back to a plain Write there).
func neoPixelRGBWWrite(conn connection.Connection, data []byte, resetBytes int) error {
	if re, ok := conn.(connection.ResetExtender); ok {
		return re.WriteExt(data, resetBytes)
	}
	return conn.Write(data)
}

// neoPixelRGBWMinimalBase is the shared minimal-tier struct for 4-channel
// (RGBW) NeoPixel-protocol LED drivers, embedded by concrete chip types
// (e.g. SK6812RGBWMinimal). Wraps a connection.Connection and maintains
// an internal RGBW pixel buffer in wire order. `Fill` updates every pixel
// and transmits immediately; `Off` is shorthand for `Fill(0, 0, 0, 0)`.
type neoPixelRGBWMinimalBase struct {
	connection   connection.Connection
	n            int
	buf          []byte
	channelOrder [4]int // wire[k] = [r,g,b,w][channelOrder[k]]
	resetBytes   int
}

// newNeoPixelRGBWMinimalBase creates a new base bound to the given
// NeoPixel connection, pixel count (clamped to MaxPixelsNeoPixelRGBW),
// wire channel order, and reset pulse length.
func newNeoPixelRGBWMinimalBase(t connection.Connection, n int, channelOrder [4]int, resetBytes int) *neoPixelRGBWMinimalBase {
	if n > MaxPixelsNeoPixelRGBW {
		n = MaxPixelsNeoPixelRGBW
	}
	if n < 0 {
		n = 0
	}
	return &neoPixelRGBWMinimalBase{
		connection:   t,
		n:            n,
		buf:          make([]byte, n*4),
		channelOrder: channelOrder,
		resetBytes:   resetBytes,
	}
}

// Fill fills every pixel with one RGBW colour and sends to the strip
// immediately. Stores the four channels in the internal buffer using
// this chip's wire channel order, then transmits. `w` defaults to 0 for
// RGB-only usage by callers.
func (d *neoPixelRGBWMinimalBase) Fill(r, g, b, w uint8) error {
	vals := [4]uint8{r, g, b, w}
	w0 := vals[d.channelOrder[0]]
	w1 := vals[d.channelOrder[1]]
	w2 := vals[d.channelOrder[2]]
	w3 := vals[d.channelOrder[3]]
	for i := 0; i < d.n; i++ {
		d.buf[i*4+0] = w0
		d.buf[i*4+1] = w1
		d.buf[i*4+2] = w2
		d.buf[i*4+3] = w3
	}
	return neoPixelRGBWWrite(d.connection, d.buf[:d.n*4], d.resetBytes)
}

// Off turns off all pixels (fill with all zeros and send).
func (d *neoPixelRGBWMinimalBase) Off() error {
	return d.Fill(0, 0, 0, 0)
}

// neoPixelRGBWFullBase is the shared full-tier struct for 4-channel
// (RGBW) NeoPixel-protocol LED drivers, embedded by concrete chip types
// (e.g. SK6812RGBWFull). Adds per-pixel addressing, explicit Show, global
// brightness scaling, buffer rotation, and HSV fill on top of
// neoPixelRGBWMinimalBase.
type neoPixelRGBWFullBase struct {
	*neoPixelRGBWMinimalBase
	brightness uint8
}

// newNeoPixelRGBWFullBase creates a new base with default brightness 255.
func newNeoPixelRGBWFullBase(t connection.Connection, n int, channelOrder [4]int, resetBytes int) *neoPixelRGBWFullBase {
	return &neoPixelRGBWFullBase{
		neoPixelRGBWMinimalBase: newNeoPixelRGBWMinimalBase(t, n, channelOrder, resetBytes),
		brightness:              255,
	}
}

// SetPixel writes one pixel into the buffer without sending. Index is
// clamped to [0, n-1]. Call `Show` to transmit.
func (d *neoPixelRGBWFullBase) SetPixel(index int, r, g, b, w uint8) {
	if d.n == 0 {
		return
	}
	if index < 0 {
		index = 0
	}
	if index >= d.n {
		index = d.n - 1
	}
	vals := [4]uint8{r, g, b, w}
	d.buf[index*4+0] = vals[d.channelOrder[0]]
	d.buf[index*4+1] = vals[d.channelOrder[1]]
	d.buf[index*4+2] = vals[d.channelOrder[2]]
	d.buf[index*4+3] = vals[d.channelOrder[3]]
}

// Show transmits the current buffer to the strip, applying brightness
// scaling. Each channel is scaled: `sent = stored * brightness / 255`.
func (d *neoPixelRGBWFullBase) Show() error {
	scaled := make([]byte, d.n*4)
	if d.brightness == 255 {
		copy(scaled, d.buf[:d.n*4])
	} else {
		for i := 0; i < d.n*4; i++ {
			scaled[i] = uint8(uint16(d.buf[i]) * uint16(d.brightness) / 255)
		}
	}
	return neoPixelRGBWWrite(d.connection, scaled, d.resetBytes)
}

// GetBrightness returns the global brightness scalar (0–255).
func (d *neoPixelRGBWFullBase) GetBrightness() uint8 {
	return d.brightness
}

// SetBrightness sets the global brightness scalar (0–255). Applied
// non-destructively at `Show` time.
func (d *neoPixelRGBWFullBase) SetBrightness(value uint8) {
	d.brightness = value
}

// Rotate shifts the pixel buffer left by `steps` positions in whole
// pixels (wraps around). Does not transmit — call `Show` afterwards.
func (d *neoPixelRGBWFullBase) Rotate(steps int) {
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
func (d *neoPixelRGBWFullBase) FillHSV(h, s, v float32) error {
	r, g, b := hsvToRGB(h, s, v)
	return d.Fill(r, g, b, 0)
}

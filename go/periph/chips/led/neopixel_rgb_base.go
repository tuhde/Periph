// Package led contains drivers for LED strips and LED drivers.
package led

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// MaxPixelsNeoPixelRGB is the maximum supported pixel count for the
// internal RGB buffer shared by 3-channel NeoPixel-protocol chips.
// Pixel indices beyond this limit are clamped silently.
const MaxPixelsNeoPixelRGB = 256

// neoPixelRGBWrite sends data with the reset pulse length requested,
// using connection.ResetExtender where the connection supports it
// (Linux's NeoPixelConnection does; TinyGo's board-native ws2812-backed
// one does not, so this falls back to a plain Write there).
func neoPixelRGBWrite(conn connection.Connection, data []byte, resetBytes int) error {
	if re, ok := conn.(connection.ResetExtender); ok {
		return re.WriteExt(data, resetBytes)
	}
	return conn.Write(data)
}

// neoPixelRGBMinimalBase is the shared minimal-tier struct for 3-channel
// (RGB) NeoPixel-protocol LED drivers, embedded by concrete chip types
// (e.g. WS2812BMinimal). Wraps a connection.Connection and maintains an
// internal RGB pixel buffer in wire order. `Fill` updates every pixel and
// transmits immediately; `Off` is shorthand for `Fill(0, 0, 0)`.
type neoPixelRGBMinimalBase struct {
	connection   connection.Connection
	n            int
	buf          []byte
	channelOrder [3]int // wire[k] = [r,g,b][channelOrder[k]]
	resetBytes   int
}

// newNeoPixelRGBMinimalBase creates a new base bound to the given
// NeoPixel connection, pixel count (clamped to MaxPixelsNeoPixelRGB),
// wire channel order, and reset pulse length.
func newNeoPixelRGBMinimalBase(t connection.Connection, n int, channelOrder [3]int, resetBytes int) *neoPixelRGBMinimalBase {
	if n > MaxPixelsNeoPixelRGB {
		n = MaxPixelsNeoPixelRGB
	}
	if n < 0 {
		n = 0
	}
	return &neoPixelRGBMinimalBase{
		connection:   t,
		n:            n,
		buf:          make([]byte, n*3),
		channelOrder: channelOrder,
		resetBytes:   resetBytes,
	}
}

// Fill fills every pixel with one colour and sends to the strip
// immediately. Stores the three channels in the internal buffer using
// this chip's wire channel order, then transmits.
func (d *neoPixelRGBMinimalBase) Fill(r, g, b uint8) error {
	vals := [3]uint8{r, g, b}
	w0 := vals[d.channelOrder[0]]
	w1 := vals[d.channelOrder[1]]
	w2 := vals[d.channelOrder[2]]
	for i := 0; i < d.n; i++ {
		d.buf[i*3+0] = w0
		d.buf[i*3+1] = w1
		d.buf[i*3+2] = w2
	}
	return neoPixelRGBWrite(d.connection, d.buf[:d.n*3], d.resetBytes)
}

// Off turns off all pixels (fill with black and send).
func (d *neoPixelRGBMinimalBase) Off() error {
	return d.Fill(0, 0, 0)
}

// neoPixelRGBFullBase is the shared full-tier struct for 3-channel (RGB)
// NeoPixel-protocol LED drivers, embedded by concrete chip types (e.g.
// WS2812BFull). Adds per-pixel addressing, explicit Show, global
// brightness scaling, buffer rotation, and HSV fill on top of
// neoPixelRGBMinimalBase.
type neoPixelRGBFullBase struct {
	*neoPixelRGBMinimalBase
	brightness uint8
}

// newNeoPixelRGBFullBase creates a new base with default brightness 255.
func newNeoPixelRGBFullBase(t connection.Connection, n int, channelOrder [3]int, resetBytes int) *neoPixelRGBFullBase {
	return &neoPixelRGBFullBase{
		neoPixelRGBMinimalBase: newNeoPixelRGBMinimalBase(t, n, channelOrder, resetBytes),
		brightness:             255,
	}
}

// SetPixel writes one pixel into the buffer without sending. Index is
// clamped to [0, n-1]. Call `Show` to transmit.
func (d *neoPixelRGBFullBase) SetPixel(index int, r, g, b uint8) {
	if d.n == 0 {
		return
	}
	if index < 0 {
		index = 0
	}
	if index >= d.n {
		index = d.n - 1
	}
	vals := [3]uint8{r, g, b}
	d.buf[index*3+0] = vals[d.channelOrder[0]]
	d.buf[index*3+1] = vals[d.channelOrder[1]]
	d.buf[index*3+2] = vals[d.channelOrder[2]]
}

// Show transmits the current buffer to the strip, applying brightness
// scaling. Each channel is scaled: `sent = stored * brightness / 255`.
func (d *neoPixelRGBFullBase) Show() error {
	if d.brightness == 255 {
		return neoPixelRGBWrite(d.connection, d.buf[:d.n*3], d.resetBytes)
	}
	scaled := make([]byte, d.n*3)
	for i := 0; i < d.n*3; i++ {
		scaled[i] = uint8(uint16(d.buf[i]) * uint16(d.brightness) / 255)
	}
	return neoPixelRGBWrite(d.connection, scaled, d.resetBytes)
}

// GetBrightness returns the global brightness scalar (0–255).
func (d *neoPixelRGBFullBase) GetBrightness() uint8 {
	return d.brightness
}

// SetBrightness sets the global brightness scalar (0–255). Applied
// non-destructively at `Show` time.
func (d *neoPixelRGBFullBase) SetBrightness(value uint8) {
	d.brightness = value
}

// Rotate shifts the pixel buffer left by `steps` positions (wraps
// around). Does not transmit — call `Show` afterwards.
func (d *neoPixelRGBFullBase) Rotate(steps int) {
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
func (d *neoPixelRGBFullBase) FillHSV(h, s, v float32) error {
	r, g, b := hsvToRGB(h, s, v)
	return d.Fill(r, g, b)
}

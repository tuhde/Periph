// Package led contains drivers for LED strips and LED drivers.
package led

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// MaxPixelsAPA102 is the maximum supported pixel count for the
// internal BGR+brightness buffer. Pixel indices beyond this limit are
// clamped silently.
const MaxPixelsAPA102 = 256

// APA102Minimal is the APA102 addressable RGB LED strip driver —
// minimal interface.
//
// Wraps a `*SPIConnection` and maintains an internal BGR+brightness
// pixel buffer. `Fill` updates every pixel and transmits the full
// APA102 frame (start + pixels + end) immediately; `Off` is shorthand
// for `Fill(0, 0, 0)`.
//
// The APA102 frame format is:
//   start frame: 4 zero-bytes (0x00 × 4)
//   pixel data:  n × 4 bytes [0xE0|brightness, B, G, R] (BGR wire order)
//   end frame:   max(4, (n+15)//16) bytes of 0xFF
type APA102Minimal struct {
	connection connection.Connection
	n          int
	buf        []byte // size = n*4; per-pixel: [0xE0|brightness, B, G, R]
}

// NewAPA102Minimal creates a new APA102Minimal bound to the given
// SPI connection and pixel count. The pixel count is clamped to
// `MaxPixelsAPA102`.
func NewAPA102Minimal(t connection.Connection, n int) (*APA102Minimal, error) {
	if n > MaxPixelsAPA102 {
		n = MaxPixelsAPA102
	}
	if n < 0 {
		n = 0
	}
	buf := make([]byte, n*4)
	// Initialize buffer with hardware brightness=31, all channels off
	for i := 0; i < n; i++ {
		buf[i*4+0] = 0xE0 | 31 // brightness byte (3 high bits = 1)
		buf[i*4+1] = 0         // blue
		buf[i*4+2] = 0         // green
		buf[i*4+3] = 0         // red
	}
	return &APA102Minimal{
		connection: t,
		n:          n,
		buf:        buf,
	}, nil
}

// Fill fills every pixel with one colour and sends to the strip
// immediately. Each channel is clamped to [0, 255]. Stores
// brightness/B/G/R in the internal buffer (BGR wire order with
// hardware brightness byte first) then transmits the full APA102
// frame.
func (d *APA102Minimal) Fill(r, g, b uint8) error {
	for i := 0; i < d.n; i++ {
		d.buf[i*4+0] = 0xE0 | 31 // hardware brightness = 31 (max)
		d.buf[i*4+1] = b         // blue
		d.buf[i*4+2] = g         // green
		d.buf[i*4+3] = r         // red
	}
	return d.sendFrame()
}

// Off turns off all pixels (fill with black and send).
// Equivalent to `Fill(0, 0, 0)`.
func (d *APA102Minimal) Off() error {
	return d.Fill(0, 0, 0)
}

// sendFrame constructs and transmits the full APA102 frame:
// start frame (4×0x00) + pixel buffer + end frame (0xFF bytes).
func (d *APA102Minimal) sendFrame() error {
	endBytes := (d.n + 15) / 16
	if endBytes < 4 {
		endBytes = 4
	}
	frame := make([]byte, 4+d.n*4+endBytes)
	frame[0] = 0x00
	frame[1] = 0x00
	frame[2] = 0x00
	frame[3] = 0x00
	copy(frame[4:4+d.n*4], d.buf)
	for i := 0; i < endBytes; i++ {
		frame[4+d.n*4+i] = 0xFF
	}
	return d.connection.Write(frame)
}

// APA102Full is the APA102 addressable RGB LED strip driver — full
// interface. Extends APA102Minimal with per-pixel addressing with
// per-pixel hardware brightness, explicit `Show`, global software
// brightness scaling, buffer rotation, and HSV fill.
//
// Embeds APA102Minimal to inherit Fill, Off, and the constructor;
// exposes APA102Full-only methods below.
type APA102Full struct {
	*APA102Minimal
	brightness uint8 // global software brightness (0–255)
}

// NewAPA102Full creates a new APA102Full with default brightness 255.
func NewAPA102Full(t connection.Connection, n int) (*APA102Full, error) {
	m, err := NewAPA102Minimal(t, n)
	if err != nil {
		return nil, err
	}
	return &APA102Full{
		APA102Minimal: m,
		brightness:    255,
	}, nil
}

// SetPixel writes one pixel into the buffer without sending. Index is
// clamped to [0, n-1]; each RGB channel is clamped to [0, 255];
// pixelBrightness is clamped to [0, 31]. Call `Show` to transmit.
func (d *APA102Full) SetPixel(index int, r, g, b, pixelBrightness uint8) {
	if d.n == 0 {
		return
	}
	if index < 0 {
		index = 0
	}
	if index >= d.n {
		index = d.n - 1
	}
	if pixelBrightness > 31 {
		pixelBrightness = 31
	}
	d.buf[index*4+0] = 0xE0 | pixelBrightness
	d.buf[index*4+1] = b
	d.buf[index*4+2] = g
	d.buf[index*4+3] = r
}

// SetPixelDefaultBrightness writes one pixel into the buffer with
// default pixel_brightness=31.
func (d *APA102Full) SetPixelDefaultBrightness(index int, r, g, b uint8) {
	d.SetPixel(index, r, g, b, 31)
}

// Show transmits the current buffer to the strip, applying software
// brightness scaling. Each RGB channel value is scaled:
// `sent = stored * brightness / 255`. The per-pixel hardware
// brightness byte is NOT scaled.
func (d *APA102Full) Show() error {
	endBytes := (d.n + 15) / 16
	if endBytes < 4 {
		endBytes = 4
	}
	pixelDataLen := d.n * 4
	totalLen := 4 + pixelDataLen + endBytes

	frame := make([]byte, totalLen)
	frame[0] = 0x00
	frame[1] = 0x00
	frame[2] = 0x00
	frame[3] = 0x00

	if d.brightness == 255 {
		copy(frame[4:4+pixelDataLen], d.buf)
	} else {
		for i := 0; i < d.n; i++ {
			base := i * 4
			frame[4+base+0] = d.buf[base+0]                                                  // hardware brightness
			frame[4+base+1] = uint8(uint16(d.buf[base+1]) * uint16(d.brightness) / 255) // blue
			frame[4+base+2] = uint8(uint16(d.buf[base+2]) * uint16(d.brightness) / 255) // green
			frame[4+base+3] = uint8(uint16(d.buf[base+3]) * uint16(d.brightness) / 255) // red
		}
	}

	for i := 0; i < endBytes; i++ {
		frame[4+pixelDataLen+i] = 0xFF
	}

	return d.connection.Write(frame)
}

// SetPixels writes multiple pixels from a slice of [r, g, b] or
// [r, g, b, pixelBrightness] slices. Missing brightness defaults to
// 31. Extra entries beyond the strip length are ignored. Does not
// transmit — call `Show` afterwards.
func (d *APA102Full) SetPixels(colors [][]uint8) {
	for i, color := range colors {
		if i >= d.n {
			break
		}
		r := color[0]
		g := color[1]
		b := color[2]
		pixelBrightness := uint8(31)
		if len(color) > 3 {
			pixelBrightness = color[3]
			if pixelBrightness > 31 {
				pixelBrightness = 31
			}
		}
		d.buf[i*4+0] = 0xE0 | pixelBrightness
		d.buf[i*4+1] = b
		d.buf[i*4+2] = g
		d.buf[i*4+3] = r
	}
}

// GetBrightness returns the global software brightness scalar (0–255).
func (d *APA102Full) GetBrightness() uint8 {
	return d.brightness
}

// SetBrightness sets the global software brightness scalar (0–255).
// Applied non-destructively at `Show` time: stored RGB values are
// unchanged. The per-pixel hardware brightness byte is NOT affected.
func (d *APA102Full) SetBrightness(value uint8) {
	d.brightness = value
}

// Rotate shifts the pixel buffer left by `steps` whole-pixel
// positions (wraps around). Each step shifts 4 bytes (one
// BGR+brightness pixel). Does not transmit — call `Show` afterwards.
func (d *APA102Full) Rotate(steps int) {
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

// FillHSV converts HSV (all inputs 0.0–1.0) to RGB and calls Fill at
// hardware brightness 31.
func (d *APA102Full) FillHSV(h, s, v float32) error {
	r, g, b := hsvToRGB(h, s, v)
	return d.Fill(r, g, b)
}
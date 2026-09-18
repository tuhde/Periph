// Package led contains drivers for LED strips and LED drivers.
package led

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// MaxPixelsSK6812RGBW is the maximum supported pixel count for the
// internal GRBW buffer. Pixel indices beyond this limit are clamped
// silently.
const MaxPixelsSK6812RGBW = MaxPixelsNeoPixelRGBW

var sk6812rgbwChannelOrder = [4]int{1, 0, 2, 3} // GRBW: wire[0]=G, wire[1]=R, wire[2]=B, wire[3]=W

// sk6812RgbwResetBytes is the reset-pulse length (in trailing zero bytes,
// post bit-encoding) requested via connection.ResetExtender to guarantee
// the SK6812RGBW's ≥80 µs reset pulse - longer than a plain Write's
// default ~53 µs (correct for WS2812B, too short for this chip). Padding
// the *pre-encoded* pixel buffer with extra zero bytes instead would not
// achieve this: those bytes get bit-encoded as more zero-value data bits
// (periodic low-with-brief-highs), not a continuous low - see
// connection.ResetExtender's doc comment.
const sk6812RgbwResetBytes = 24

// SK6812RGBWMinimal is the SK6812RGBW addressable RGBW LED strip driver
// — minimal interface.
//
// Embeds the shared neoPixelRGBWMinimalBase, fixing GRBW wire order and
// this chip's 24-byte (~80 µs) extended reset. `Fill` updates every
// pixel and transmits immediately; `Off` is shorthand for
// `Fill(0, 0, 0, 0)`.
type SK6812RGBWMinimal struct {
	*neoPixelRGBWMinimalBase
}

// NewSK6812RGBWMinimal creates a new SK6812RGBWMinimal bound to the
// given NeoPixel connection and pixel count. The pixel count is
// clamped to `MaxPixelsSK6812RGBW`.
func NewSK6812RGBWMinimal(t connection.Connection, n int) (*SK6812RGBWMinimal, error) {
	return &SK6812RGBWMinimal{
		neoPixelRGBWMinimalBase: newNeoPixelRGBWMinimalBase(t, n, sk6812rgbwChannelOrder, sk6812RgbwResetBytes),
	}, nil
}

// SK6812RGBWFull is the SK6812RGBW addressable RGBW LED strip driver
// — full interface. Adds per-pixel addressing, explicit `Show`, global
// brightness scaling, buffer rotation, and HSV fill.
//
// Embeds the shared neoPixelRGBWFullBase, fixing GRBW wire order and
// this chip's 24-byte (~80 µs) extended reset.
type SK6812RGBWFull struct {
	*neoPixelRGBWFullBase
}

// NewSK6812RGBWFull creates a new SK6812RGBWFull with default brightness 255.
func NewSK6812RGBWFull(t connection.Connection, n int) (*SK6812RGBWFull, error) {
	return &SK6812RGBWFull{
		neoPixelRGBWFullBase: newNeoPixelRGBWFullBase(t, n, sk6812rgbwChannelOrder, sk6812RgbwResetBytes),
	}, nil
}

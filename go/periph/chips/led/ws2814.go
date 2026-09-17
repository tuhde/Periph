// Package led contains drivers for LED strips and LED drivers.
package led

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// MaxPixelsWS2814 is the maximum supported pixel count for the internal
// RGBW buffer. Pixel indices beyond this limit are clamped silently.
const MaxPixelsWS2814 = MaxPixelsNeoPixelRGBW

var ws2814ChannelOrder = [4]int{0, 1, 2, 3} // RGBW: identity, no reorder

// ws2814ResetBytes is the reset-pulse length (in trailing zero bytes,
// post bit-encoding) requested via connection.ResetExtender to guarantee
// the WS2814's ≥280 µs reset pulse - longer than both WS2812B's ~53 µs
// default and SK6812RGBW's 24-byte (~80 µs) extension. See
// sk6812rgbw.go's sk6812RgbwResetBytes for why this must be a
// connection-level reset request rather than padding the pixel buffer.
const ws2814ResetBytes = 90

// WS2814Minimal is the WS2814 addressable RGBW LED strip driver —
// minimal interface.
//
// Embeds the shared neoPixelRGBWMinimalBase, fixing identity RGBW wire
// order (no reorder) and this chip's 90-byte (~300 µs) extended reset.
// `Fill` updates every pixel and transmits immediately; `Off` is
// shorthand for `Fill(0, 0, 0, 0)`.
type WS2814Minimal struct {
	*neoPixelRGBWMinimalBase
}

// NewWS2814Minimal creates a new WS2814Minimal bound to the given
// NeoPixel connection and pixel count. The pixel count is clamped to
// `MaxPixelsWS2814`.
func NewWS2814Minimal(t connection.Connection, n int) (*WS2814Minimal, error) {
	return &WS2814Minimal{
		neoPixelRGBWMinimalBase: newNeoPixelRGBWMinimalBase(t, n, ws2814ChannelOrder, ws2814ResetBytes),
	}, nil
}

// WS2814Full is the WS2814 addressable RGBW LED strip driver — full
// interface. Adds per-pixel addressing, explicit `Show`, global
// brightness scaling, buffer rotation, and HSV fill.
//
// Embeds the shared neoPixelRGBWFullBase, fixing identity RGBW wire
// order (no reorder) and this chip's 90-byte (~300 µs) extended reset.
type WS2814Full struct {
	*neoPixelRGBWFullBase
}

// NewWS2814Full creates a new WS2814Full with default brightness 255.
func NewWS2814Full(t connection.Connection, n int) (*WS2814Full, error) {
	return &WS2814Full{
		neoPixelRGBWFullBase: newNeoPixelRGBWFullBase(t, n, ws2814ChannelOrder, ws2814ResetBytes),
	}, nil
}

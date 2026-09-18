// Package led contains drivers for LED strips and LED drivers.
package led

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// MaxPixelsWS2812B is the maximum supported pixel count for the
// internal GRB buffer. Pixel indices beyond this limit are clamped
// silently.
const MaxPixelsWS2812B = MaxPixelsNeoPixelRGB

var ws2812bChannelOrder = [3]int{1, 0, 2} // GRB: wire[0]=G, wire[1]=R, wire[2]=B

const ws2812bResetBytes = 16 // ~53us, WS2812B's default minimum

// WS2812BMinimal is the WS2812B addressable RGB LED strip driver —
// minimal interface.
//
// Embeds the shared neoPixelRGBMinimalBase, fixing GRB wire order and
// WS2812B's default reset length. `Fill` updates every pixel and
// transmits immediately; `Off` is shorthand for `Fill(0, 0, 0)`.
type WS2812BMinimal struct {
	*neoPixelRGBMinimalBase
}

// NewWS2812BMinimal creates a new WS2812BMinimal bound to the given
// NeoPixel connection and pixel count. The pixel count is clamped to
// `MaxPixelsWS2812B`.
func NewWS2812BMinimal(t connection.Connection, n int) (*WS2812BMinimal, error) {
	return &WS2812BMinimal{
		neoPixelRGBMinimalBase: newNeoPixelRGBMinimalBase(t, n, ws2812bChannelOrder, ws2812bResetBytes),
	}, nil
}

// WS2812BFull is the WS2812B addressable RGB LED strip driver — full
// interface. Adds per-pixel addressing, explicit `Show`, global
// brightness scaling, buffer rotation, and HSV fill.
//
// Embeds the shared neoPixelRGBFullBase, fixing GRB wire order and
// WS2812B's default reset length.
type WS2812BFull struct {
	*neoPixelRGBFullBase
}

// NewWS2812BFull creates a new WS2812BFull with default brightness 255.
func NewWS2812BFull(t connection.Connection, n int) (*WS2812BFull, error) {
	return &WS2812BFull{
		neoPixelRGBFullBase: newNeoPixelRGBFullBase(t, n, ws2812bChannelOrder, ws2812bResetBytes),
	}, nil
}

// hsvToRGB converts an HSV colour (each channel 0.0–1.0) to RGB bytes
// (each channel 0–255). Uses the standard sector-based algorithm.
// Shared by every NeoPixel-protocol chip driver in this package.
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

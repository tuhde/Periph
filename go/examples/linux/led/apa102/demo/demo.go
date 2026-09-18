// APA102 demo example — 13-bit effective color depth demonstration.
// go build ./go/examples/linux/led/apa102/demo/
package main

import (
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func hsvToRGB(h, s, v float32) (uint8, uint8, uint8) {
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

func main() {
	bus, _ := strconv.Atoi(os.Getenv("SPI_BUS"))
	device, _ := strconv.Atoi(os.Getenv("SPI_DEVICE"))

	conn, err := connection.NewSPIConnection(bus, device, 1_000_000, nil, nil)
	if err != nil {
		panic(err)
	}

	const N_PIXELS = 30
	const FRAME_MS = 16 // ~60 fps
	const RAINBOW_MS = 10000

	strip, err := led.NewAPA102Full(conn, N_PIXELS)
	if err != nil {
		panic(err)
	}

	// --- 13-bit effective color depth demonstration ---
	// First pass: full hardware brightness (31) for maximum drive current
	// Second pass: hardware brightness 1 (1/31 current) to show hardware vs software dimming

	// --- Pass 1: Full hardware brightness (31) ---
	// Rainbow sweep at hardware brightness 31 uses full 8-bit PWM channels + 5-bit
	// hardware current control = 13-bit effective depth per channel.
	strip.SetBrightness(255)                                     // Set global software brightness, (value=0–255) → ()
	hueOffset := float32(0.0)
	start := time.Now()
	lastPrint := start
	for time.Since(start).Milliseconds() < RAINBOW_MS {
		for i := 0; i < N_PIXELS; i++ {
			h := float32(math.Mod(float64(hueOffset)+float64(i)/float64(N_PIXELS), 1.0))
			r, g, b := hsvToRGB(h, 1.0, 1.0)
			strip.SetPixel(i, r, g, b, 31)                       // Set pixel i to rainbow hue at hw brightness 31, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
		}
		strip.Show()                                             // Transmit buffer to strip, () → error
																 // applies software brightness scaling then calls connection.Write()
		hueOffset = float32(math.Mod(float64(hueOffset)+1.0/float64(N_PIXELS*2), 1.0))
		now := time.Now()
		if now.Sub(lastPrint).Seconds() >= 1 {
			println("rainbow hw_brightness=31 hue_offset=", hueOffset)
			lastPrint = now
		}
		elapsed := time.Since(now).Milliseconds()
		if elapsed < FRAME_MS {
			time.Sleep(time.Duration(FRAME_MS-elapsed) * time.Millisecond)
		}
	}

	// --- Pass 2: Low hardware brightness (1) ---
	// Same 8-bit RGB values but hardware brightness=1 (1/31 drive current).
	// Demonstrates hardware current control vs software brightness scaling.
	strip.SetBrightness(255)                                     // Set global software brightness, (value=0–255) → ()
	hueOffset = 0.0
	start = time.Now()
	lastPrint = start
	for time.Since(start).Milliseconds() < RAINBOW_MS {
		for i := 0; i < N_PIXELS; i++ {
			h := float32(math.Mod(float64(hueOffset)+float64(i)/float64(N_PIXELS), 1.0))
			r, g, b := hsvToRGB(h, 1.0, 1.0)
			strip.SetPixel(i, r, g, b, 1)                        // Set pixel i to rainbow hue at hw brightness 1, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
		}
		strip.Show()                                             // Transmit buffer to strip, () → error
																 // applies software brightness scaling then calls connection.Write()
		hueOffset = float32(math.Mod(float64(hueOffset)+1.0/float64(N_PIXELS*2), 1.0))
		now := time.Now()
		if now.Sub(lastPrint).Seconds() >= 1 {
			println("rainbow hw_brightness=1 hue_offset=", hueOffset)
			lastPrint = now
		}
		elapsed := time.Since(now).Milliseconds()
		if elapsed < FRAME_MS {
			time.Sleep(time.Duration(FRAME_MS-elapsed) * time.Millisecond)
		}
	}

	strip.Off()                                                  // Turn off all pixels, () → error
	time.Sleep(time.Second)
}
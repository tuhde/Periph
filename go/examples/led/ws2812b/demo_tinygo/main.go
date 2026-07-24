//go:build tinygo

// WS2812B demo example — TinyGo / Raspberry Pi Pico W.
//
// Color-wheel sweep on a Pico W. Rotates the rainbow for 10 s,
// strobes white for 2 s, then resumes the rainbow. Run for
// N_PIXELS (default 30) LEDs on GP22.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/transport"
)

const (
	frameMs      = 33
	rainbowSecs  = 10
	strobeSecs   = 2
	strobeHalfMs = 50
)

func main() {
	const n = 30
	tr := transport.NewNeoPixelTransport(machine.GP22) // Create NeoPixel transport, (pin=GP22) → (*NeoPixelTransport)
	strip, err := led.NewWS2812BFull(tr, n)            // Create WS2812B driver, (transport, n=30) → (*WS2812BFull, error)
	if err != nil {
		panic(err)
	}
	strip.SetBrightness(180) // Set global brightness, (value=180) → ()

	// --- Rainbow rotation ---
	hueOffset := float32(0.0)
	lastPrint := time.Now()
	rainbowStart := time.Now()
	for time.Since(rainbowStart).Seconds() < float64(rainbowSecs) {
		frameStart := time.Now()
		for i := 0; i < n; i++ {
			h := float32(math.Mod(float64(hueOffset)+float64(i)/float64(n), 1.0))
			r, g, b := hsvToRGB(h, 1.0, 1.0)
			strip.SetPixel(i, r, g, b) // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
		}
		if err := strip.Show(); err != nil { // Transmit buffer, () → error
			fmt.Printf("show rainbow: %v\n", err)
		}
		hueOffset = float32(math.Mod(float64(hueOffset)+1.0/float64(n*2), 1.0))
		if time.Since(lastPrint).Seconds() >= 1 {
			fmt.Printf("rainbow hue_offset=%.3f\n", hueOffset)
			lastPrint = time.Now()
		}
		elapsed := time.Since(frameStart).Milliseconds()
		if elapsed < frameMs {
			time.Sleep(time.Duration(frameMs-elapsed) * time.Millisecond)
		}
	}

	// --- Strobe effect ---
	strip.SetBrightness(255)                          // Set global brightness, (value=255) → ()
	strip.Fill(255, 255, 255)                          // Pre-load white into buffer, (r=255, g=255, b=255) → error
	strobeStart := time.Now()
	state := true
	for time.Since(strobeStart).Seconds() < float64(strobeSecs) {
		if state {
			strip.SetBrightness(255) // Set global brightness, (value=255) → ()
		} else {
			strip.SetBrightness(0) // Set global brightness, (value=0) → ()
		}
		if err := strip.Show(); err != nil { // Transmit buffer, () → error
			fmt.Printf("show strobe: %v\n", err)
		}
		state = !state
		time.Sleep(strobeHalfMs * time.Millisecond)
	}

	// --- Continuous rainbow ---
	strip.SetBrightness(180) // Set global brightness, (value=180) → ()
	hueOffset = 0.0
	lastPrint = time.Now()
	for {
		frameStart := time.Now()
		for i := 0; i < n; i++ {
			h := float32(math.Mod(float64(hueOffset)+float64(i)/float64(n), 1.0))
			r, g, b := hsvToRGB(h, 1.0, 1.0)
			strip.SetPixel(i, r, g, b) // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
		}
		if err := strip.Show(); err != nil { // Transmit buffer, () → error
			fmt.Printf("show rainbow: %v\n", err)
		}
		hueOffset = float32(math.Mod(float64(hueOffset)+1.0/float64(n*2), 1.0))
		if time.Since(lastPrint).Seconds() >= 1 {
			fmt.Printf("rainbow hue_offset=%.3f\n", hueOffset)
			lastPrint = time.Now()
		}
		elapsed := time.Since(frameStart).Milliseconds()
		if elapsed < frameMs {
			time.Sleep(time.Duration(frameMs-elapsed) * time.Millisecond)
		}
	}
}

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

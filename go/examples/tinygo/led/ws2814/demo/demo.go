//go:build tinygo

// WS2814 demo example — TinyGo / Raspberry Pi Pico W.
//
// Begins with 5 seconds of rainbow rotation (RGB channels, w=0 per
// pixel) at ~30 fps, then flashes warm white (r=255, g=200, b=150,
// w=255) at full brightness for 2 seconds, then dims the warm white
// to 50% using the brightness property and holds for 2 seconds, then
// cycles to cool white (r=200, g=210, b=255, w=255). Prints the
// current mode and brightness once per second.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	frameMs     = 33
	rainbowSecs = 5
	flashSecs   = 2
	dimSecs     = 2
)

func main() {
	const n = 30
	conn := connection.NewNeoPixelConnection(machine.GP22, nil) // Create NeoPixel connection, (pin=GP22) → (*NeoPixelConnection)
	strip, err := led.NewWS2814Full(conn, n)                    // Create WS2814 driver, (connection, n=30) → (*WS2814Full, error)
	if err != nil {
		panic(err)
	}

	// --- Rainbow rotation using RGB channels (white=0). ---
	hueOffset := float32(0.0)
	lastPrint := time.Now()
	rainbowStart := time.Now()
	for time.Since(rainbowStart).Seconds() < float64(rainbowSecs) {
		frameStart := time.Now()
		for i := 0; i < n; i++ {
			h := float32(math.Mod(float64(hueOffset)+float64(i)/float64(n), 1.0))
			r, g, b := hsvToRGB(h, 1.0, 1.0)
			strip.SetPixel(i, r, g, b, 0) // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → ()
		}
		if err := strip.Show(); err != nil {
			fmt.Printf("show rainbow: %v\n", err)
		}
		hueOffset = float32(math.Mod(float64(hueOffset)+1.0/float64(n*2), 1.0))
		if time.Since(lastPrint).Seconds() >= 1 {
			fmt.Printf("mode=rainbow brightness=%d\n", strip.GetBrightness())
			lastPrint = time.Now()
		}
		elapsed := time.Since(frameStart).Milliseconds()
		if elapsed < frameMs {
			time.Sleep(time.Duration(frameMs-elapsed) * time.Millisecond)
		}
	}

	// --- Warm white at full brightness for 2 seconds. ---
	if err := strip.Fill(255, 200, 150, 255); err != nil {
		fmt.Printf("fill warm white: %v\n", err)
	}
	flashStart := time.Now()
	for time.Since(flashStart).Seconds() < float64(flashSecs) {
		fmt.Printf("mode=warm-white brightness=%d\n", strip.GetBrightness())
		time.Sleep(100 * time.Millisecond)
	}

	// --- Dim warm white to 50% for 2 seconds. ---
	strip.SetBrightness(128)
	if err := strip.Show(); err != nil {
		fmt.Printf("show dimmed: %v\n", err)
	}
	dimStart := time.Now()
	for time.Since(dimStart).Seconds() < float64(dimSecs) {
		fmt.Printf("mode=warm-white-dimmed brightness=%d\n", strip.GetBrightness())
		time.Sleep(100 * time.Millisecond)
	}

	// --- Cycle to cool white at full brightness. ---
	strip.SetBrightness(255)
	if err := strip.Fill(200, 210, 255, 255); err != nil {
		fmt.Printf("fill cool white: %v\n", err)
	}
	fmt.Printf("mode=cool-white brightness=%d\n", strip.GetBrightness())
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

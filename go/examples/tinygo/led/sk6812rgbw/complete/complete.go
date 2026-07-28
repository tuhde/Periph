//go:build tinygo

// SK6812RGBW complete example — TinyGo / Raspberry Pi Pico W.
//
// Wires the SK6812RGBW DIN to GP22. Exercises every method in
// the SK6812RGBWFull API: pixel addressing, show, brightness,
// rotation, and HSV fill.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	const n = 8
	conn := connection.NewNeoPixelConnection(machine.GP22, nil) // Create NeoPixel connection, (pin=GP22) → (*NeoPixelConnection)
	strip, err := led.NewSK6812RGBWFull(conn, n)          // Create SK6812RGBW driver, (connection, n=8) → (*SK6812RGBWFull, error)
	if err != nil {
		panic(err)
	}

	fmt.Printf("brightness: %d\n", strip.GetBrightness()) // Get global brightness, () → uint8

	strip.SetPixel(0, 255, 0, 0, 0)   // Set pixel 0 red, (index=0, r=255, g=0, b=0, w=0) → ()
	strip.SetPixel(n-1, 0, 0, 0, 255) // Set last pixel warm white, (index=n-1, r=0, g=0, b=0, w=255) → ()
	if err := strip.Show(); err != nil {
		fmt.Printf("show: %v\n", err)
	}
	time.Sleep(500 * time.Millisecond)

	strip.SetBrightness(128) // Set global brightness, (value=128) → ()
	fmt.Printf("brightness after set: %d\n", strip.GetBrightness())
	if err := strip.Show(); err != nil {
		fmt.Printf("show brightness: %v\n", err)
	}
	time.Sleep(500 * time.Millisecond)

	strip.SetBrightness(255) // Set global brightness, (value=255) → ()
	strip.Rotate(1)          // Shift buffer left by 1 pixel, (steps=1) → ()
	if err := strip.Show(); err != nil {
		fmt.Printf("show rotate: %v\n", err)
	}
	time.Sleep(500 * time.Millisecond)

	if err := strip.FillHSV(0.0, 1.0, 1.0); err != nil {
		fmt.Printf("fill_hsv 0.0: %v\n", err)
	}
	time.Sleep(500 * time.Millisecond)
	if err := strip.FillHSV(0.333, 1.0, 1.0); err != nil {
		fmt.Printf("fill_hsv 0.333: %v\n", err)
	}
	time.Sleep(500 * time.Millisecond)
	if err := strip.FillHSV(0.667, 1.0, 1.0); err != nil {
		fmt.Printf("fill_hsv 0.667: %v\n", err)
	}
	time.Sleep(500 * time.Millisecond)

	if err := strip.Off(); err != nil {
		fmt.Printf("off: %v\n", err)
	}
}

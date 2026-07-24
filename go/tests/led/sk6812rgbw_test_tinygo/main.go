//go:build tinygo

// SK6812RGBW hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W with the SK6812RGBW DIN on GP22. Runs the
// SK6812RGBW check sequence: smoke tests for both Minimal and
// Full, fill (including the dedicated white channel), set_pixel,
// show, brightness scaling, rotation, and HSV fill. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... ===
// line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	tr := transport.NewNeoPixelTransport(machine.GP22) // Create NeoPixel transport, (pin=GP22) → (*NeoPixelTransport)

	// --- SK6812RGBWMinimal ---
	{
		strip, err := led.NewSK6812RGBWMinimal(tr, 8) // Create SK6812RGBW driver, (transport, n=8) → (*SK6812RGBWMinimal, error)
		check("minimal_construct", err == nil && strip != nil)
		if err == nil {
			check("fill_red_ok", strip.Fill(255, 0, 0, 0) == nil)   // Fill red, (r=255, g=0, b=0, w=0) → error
			check("fill_green_ok", strip.Fill(0, 255, 0, 0) == nil) // Fill green, (r=0, g=255, b=0, w=0) → error
			check("fill_blue_ok", strip.Fill(0, 0, 255, 0) == nil)  // Fill blue, (r=0, g=0, b=255, w=0) → error
			check("fill_white_ok", strip.Fill(0, 0, 0, 255) == nil) // Fill white channel, (r=0, g=0, b=0, w=255) → error
			check("off_ok", strip.Off() == nil)                     // Turn off, () → error
		}
	}

	// --- SK6812RGBWFull ---
	{
		strip, err := led.NewSK6812RGBWFull(tr, 8) // Create SK6812RGBW driver, (transport, n=8) → (*SK6812RGBWFull, error)
		check("full_construct", err == nil && strip != nil)
		if err == nil {
			check("default_brightness_255", strip.GetBrightness() == 255) // Get global brightness, () → uint8

			strip.SetPixel(0, 255, 0, 0, 0)              // Set pixel 0 red, (index=0, r=255, g=0, b=0, w=0) → ()
			check("set_pixel_show_ok", strip.Show() == nil) // Transmit buffer, () → error

			strip.SetPixel(7, 0, 0, 0, 255)                       // Set last pixel warm white, (index=7, r=0, g=0, b=0, w=255) → ()
			check("set_pixel_white_show_ok", strip.Show() == nil) // Transmit buffer, () → error

			strip.SetBrightness(128)                                          // Set global brightness, (value=128) → ()
			check("brightness_set_128", strip.GetBrightness() == 128)          // Get global brightness, () → uint8
			check("show_brightness_128_ok", strip.Show() == nil)               // Transmit buffer, () → error

			strip.SetBrightness(0)                                            // Set global brightness, (value=0) → ()
			check("show_brightness_0_ok", strip.Show() == nil)                // Transmit buffer, () → error

			strip.SetBrightness(255)                                  // Set global brightness, (value=255) → ()
			strip.Rotate(1)                                           // Shift buffer left by 1 pixel, (steps=1) → ()
			check("rotate_show_ok", strip.Show() == nil)              // Transmit buffer, () → error

			check("fill_hsv_0_ok", strip.FillHSV(0.0, 1.0, 1.0) == nil)     // Fill red via HSV, (h=0.0, s=1.0, v=1.0) → error
			check("fill_hsv_0333_ok", strip.FillHSV(0.333, 1.0, 1.0) == nil) // Fill green via HSV, (h=0.333, s=1.0, v=1.0) → error
			check("fill_hsv_0667_ok", strip.FillHSV(0.667, 1.0, 1.0) == nil) // Fill blue via HSV, (h=0.667, s=1.0, v=1.0) → error
			check("off_full_ok", strip.Off() == nil)                          // Turn off, () → error
		}
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

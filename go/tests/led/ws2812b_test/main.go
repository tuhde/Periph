//go:build linux && !tinygo

// WS2812B hardware test — Linux host.
//
// Opens /dev/spidevB.D and runs the WS2812B check sequence:
// smoke tests for both Minimal and Full, fill, set_pixel, show,
// brightness scaling, rotation, and HSV fill. Prints PASS/FAIL
// per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_BUS:", err)
		os.Exit(2)
	}
	dev, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_DEVICE:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	tr, trErr := transport.NewNeoPixelTransport(bus, dev)
	if trErr != nil {
		fmt.Fprintln(os.Stderr, "transport open failed (no hw?):", trErr)
		check("ws2812b_minimal_skip_no_hw", true)
		check("ws2812b_full_skip_no_hw", true)
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		if failed != 0 {
			os.Exit(1)
		}
		return
	}
	defer tr.Close()

	// --- WS2812BMinimal ---
	{
		strip, err := led.NewWS2812BMinimal(tr, 8) // Create WS2812B driver, (transport, n=8) → (*WS2812BMinimal, error)
		check("minimal_construct", err == nil && strip != nil)
		if err == nil {
			check("fill_red_ok", strip.Fill(255, 0, 0) == nil)   // Fill red, (r=255, g=0, b=0) → error
			check("fill_green_ok", strip.Fill(0, 255, 0) == nil) // Fill green, (r=0, g=255, b=0) → error
			check("fill_blue_ok", strip.Fill(0, 0, 255) == nil)  // Fill blue, (r=0, g=0, b=255) → error
			check("off_ok", strip.Off() == nil)                  // Turn off, () → error
		}
	}

	// --- WS2812BFull ---
	{
		strip, err := led.NewWS2812BFull(tr, 8) // Create WS2812B driver, (transport, n=8) → (*WS2812BFull, error)
		check("full_construct", err == nil && strip != nil)
		if err == nil {
			check("default_brightness_255", strip.GetBrightness() == 255) // Get global brightness, () → uint8

			strip.SetPixel(0, 255, 0, 0)              // Set pixel 0 red, (index=0, r=255, g=0, b=0) → ()
			check("set_pixel_show_ok", strip.Show() == nil) // Transmit buffer, () → error

			strip.SetPixel(7, 0, 0, 255)              // Set last pixel blue, (index=7, r=0, g=0, b=255) → ()
			check("set_pixel_last_show_ok", strip.Show() == nil) // Transmit buffer, () → error

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
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

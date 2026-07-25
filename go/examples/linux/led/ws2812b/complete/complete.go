//go:build linux && !tinygo

// WS2812B complete example — Linux host.
//
// Exercises every method in the WS2812BFull API: pixel addressing,
// show, brightness, rotation, and HSV fill.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	dev, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}
	n, err := strconv.Atoi(envOr("N_PIXELS", "8"))
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewNeoPixelTransport(bus, dev) // Create NeoPixel transport, (bus=0, device=0) → (*NeoPixelTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	strip, err := led.NewWS2812BFull(tr, n) // Create WS2812B driver, (transport, n=8) → (*WS2812BFull, error)
	if err != nil {
		panic(err)
	}

	// Default brightness is 255 (full).
	fmt.Printf("brightness: %d\n", strip.GetBrightness()) // Get global brightness, () → uint8

	strip.SetPixel(0, 255, 0, 0)              // Set pixel 0 red, (index=0, r=255, g=0, b=0) → ()
	strip.SetPixel(n-1, 0, 0, 255)            // Set last pixel blue, (index=n-1, r=0, g=0, b=255) → ()
	if err := strip.Show(); err != nil {       // Transmit buffer, () → error
		fmt.Fprintln(os.Stderr, "show:", err)
	}
	time.Sleep(500 * time.Millisecond)

	strip.SetBrightness(128)                                          // Set global brightness, (value=128) → ()
	fmt.Printf("brightness after set: %d\n", strip.GetBrightness())   // Get global brightness, () → uint8
	if err := strip.Show(); err != nil {                              // Transmit buffer, () → error
		fmt.Fprintln(os.Stderr, "show brightness:", err)
	}
	time.Sleep(500 * time.Millisecond)

	strip.SetBrightness(255)                  // Set global brightness, (value=255) → ()
	strip.Rotate(1)                           // Shift buffer left by 1 pixel, (steps=1) → ()
	if err := strip.Show(); err != nil {      // Transmit buffer, () → error
		fmt.Fprintln(os.Stderr, "show rotate:", err)
	}
	time.Sleep(500 * time.Millisecond)

	if err := strip.FillHSV(0.0, 1.0, 1.0); err != nil { // Fill with red via HSV, (h=0.0, s=1.0, v=1.0) → error
		fmt.Fprintln(os.Stderr, "fill_hsv 0.0:", err)
	}
	time.Sleep(500 * time.Millisecond)
	if err := strip.FillHSV(0.333, 1.0, 1.0); err != nil { // Fill with green via HSV, (h=0.333, s=1.0, v=1.0) → error
		fmt.Fprintln(os.Stderr, "fill_hsv 0.333:", err)
	}
	time.Sleep(500 * time.Millisecond)
	if err := strip.FillHSV(0.667, 1.0, 1.0); err != nil { // Fill with blue via HSV, (h=0.667, s=1.0, v=1.0) → error
		fmt.Fprintln(os.Stderr, "fill_hsv 0.667:", err)
	}
	time.Sleep(500 * time.Millisecond)

	if err := strip.Off(); err != nil { // Turn off strip, () → error
		fmt.Fprintln(os.Stderr, "off:", err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

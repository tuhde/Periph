//go:build linux && !tinygo

// SK6812RGBW minimal example — Linux host.
//
// Constructs the driver on /dev/spidev0.0 with 8 pixels, then
// fills the strip red, green, blue, warm white, and off in turn.
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

	strip, err := led.NewSK6812RGBWMinimal(tr, n) // Create SK6812RGBW driver, (transport, n=8) → (*SK6812RGBWMinimal, error)
	if err != nil {
		panic(err)
	}

	if err := strip.Fill(255, 0, 0, 0); err != nil { // Fill strip with red, (r=255, g=0, b=0, w=0) → error
		fmt.Fprintln(os.Stderr, "fill red:", err)
	}
	time.Sleep(time.Second)
	if err := strip.Fill(0, 255, 0, 0); err != nil { // Fill strip with green, (r=0, g=255, b=0, w=0) → error
		fmt.Fprintln(os.Stderr, "fill green:", err)
	}
	time.Sleep(time.Second)
	if err := strip.Fill(0, 0, 255, 0); err != nil { // Fill strip with blue, (r=0, g=0, b=255, w=0) → error
		fmt.Fprintln(os.Stderr, "fill blue:", err)
	}
	time.Sleep(time.Second)
	if err := strip.Fill(0, 0, 0, 255); err != nil { // Fill strip with white, (r=0, g=0, b=0, w=255) → error
		fmt.Fprintln(os.Stderr, "fill white:", err)
	}
	time.Sleep(time.Second)
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

//go:build linux && !tinygo

// UC8276C minimal example — Linux host (Raspberry Pi).
//
// Waveshare 4.2inch e-Paper (B). Each refresh blocks for about 17 s.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
package main

import (
	"time"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	dc, err := connection.NewGpioOutputPin(25) // Create D/C# pin, (line=25) → (*GpioOutputPin, error)
	must(err)
	res, err := connection.NewGpioOutputPin(17) // Create RES# pin, (line=17) → (*GpioOutputPin, error)
	must(err)
	busy, err := connection.NewGpioInputPin(24) // Create BUSY_N pin, (line=24) → (*GpioInputPin, error)
	must(err)
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 4_000_000, dc, res, busy, connection.WireModeSimplex) // Create display connection, (bus, device, speedHz, dc, res, busy, wireMode)
	must(err)
	epd, err := display.NewUC8276CMinimal(conn, 400, 300, 30*time.Second) // Create UC8276C driver, (conn, width=400, height=300, busyTimeout) → (*UC8276CMinimal, error)
	must(err)
	epd.Clear(display.UC8276CWhite)            // Fill framebuffer, (color=White)
	epd.SetPixel(10, 10, display.UC8276CBlack) // Set one pixel, (x, y, color)
	for x := 20; x < 120; x++ {
		epd.SetPixel(x, 40, display.UC8276CRed) // Set one pixel, (x, y, color)
	}
	must(epd.Show())  // Refresh panel (about 17 s), () → error
	must(epd.Sleep()) // Enter deep sleep, () → error
	must(conn.Close())
}

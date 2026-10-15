//go:build linux && !tinygo

// UC8159 demo example — Linux host (Raspberry Pi).
//
// Waveshare 5.65inch e-Paper (F). Each refresh blocks for about 30 s.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
package main

import (
	"fmt"
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
	busy, err := connection.NewGpioInputPin(24) // Create BUSY pin, (line=24) → (*GpioInputPin, error)
	must(err)
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 4_000_000, dc, res, busy, connection.WireModeSimplex) // Create display connection, (bus, device, speedHz, dc, res, busy, wireMode)
	must(err)
	epd, err := display.NewUC8159Full(conn, 60*time.Second) // Create UC8159 driver, (conn, busyTimeout) → (*UC8159Full, error)
	must(err)

	// --- Compose the colour chart in the framebuffer ---
	// White page, seven vertical bars (black, white, green, blue, red, yellow, orange) and a
	// one-pixel black frame inset by 4 px (a frame on the very edge is hidden by the bezel).
	epd.Clear(display.UC8159White) // Fill framebuffer, (color)
	for c := 0; c < 7; c++ {
		epd.DrawRect(20+c*80, 40, 80, 368, uint8(c), true) // Draw rectangle, (x, y, w, h, color, fill)
	}
	epd.DrawRect(4, 4, 592, 440, display.UC8159Black, false) // Draw rectangle, (x, y, w, h, color, fill)

	// --- Refresh and time it ---
	// Show blocks for the whole DTM1/PON/DRF/POF sequence: about 30 s.
	start := time.Now()
	must(epd.Show()) // Refresh panel, () → error
	fmt.Println("refresh took", time.Since(start))

	// --- Save the image and power down ---
	// The panel keeps the picture without power; deep sleep stops the controller drawing current.
	must(epd.Sleep()) // Enter deep sleep, () → error
	must(conn.Close())
}

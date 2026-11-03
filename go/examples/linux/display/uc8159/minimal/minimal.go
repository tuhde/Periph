//go:build linux && !tinygo

// UC8159 minimal example — Linux host (Raspberry Pi).
//
// Waveshare 5.65inch e-Paper (F). Each refresh blocks for about 30 s.
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
	busy, err := connection.NewGpioInputPin(24) // Create BUSY pin, (line=24) → (*GpioInputPin, error)
	must(err)
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 4_000_000, dc, res, busy, connection.WireModeSimplex) // Create display connection, (bus, device, speedHz, dc, res, busy, wireMode)
	must(err)
	epd, err := display.NewUC8159Minimal(conn, 60*time.Second) // Create UC8159 driver, (conn, busyTimeout) → (*UC8159Minimal, error)
	must(err)
	epd.Clear(display.UC8159White) // Fill framebuffer, (color=White)
	for x := 0; x < 600; x++ {
		colour := uint8(x * 7 / 600) // seven vertical bars: black .. orange
		for y := 0; y < 448; y++ {
			epd.SetPixel(x, y, colour) // Set one pixel, (x, y, color)
		}
	}
	must(epd.Show())  // Refresh panel (about 30 s), () → error
	must(epd.Sleep()) // Enter deep sleep, () → error
	must(conn.Close())
}

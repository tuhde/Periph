//go:build linux && !tinygo

// IL0373 minimal example — Linux host (Raspberry Pi).
//
// Waveshare 2.13inch e-Paper (D). Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
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
	epd, err := display.NewIL0373Minimal(conn, 104, 212, 30*time.Second) // Create IL0373 driver, (conn, width=104, height=212, busyTimeout) → (*IL0373Minimal, error)
	must(err)
	epd.Clear(display.IL0373White)            // Fill framebuffer, (color=White)
	epd.SetPixel(10, 10, display.IL0373Black) // Set one pixel, (x, y, color)
	for x := 20; x < 100; x++ {
		epd.SetPixel(x, 40, display.IL0373Black) // Set one pixel, (x, y, color)
	}
	must(epd.Show())  // Refresh panel, () → error
	must(epd.Sleep()) // Enter deep sleep, () → error
	must(conn.Close())
}

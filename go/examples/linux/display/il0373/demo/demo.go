//go:build linux && !tinygo

// IL0373 demo example — Linux host (Raspberry Pi).
//
// Waveshare 2.13inch e-Paper (D). Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
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
	epd, err := display.NewIL0373Full(conn, 104, 212, 30*time.Second) // Create IL0373 driver, (conn, width=104, height=212, busyTimeout) → (*IL0373Full, error)
	must(err)

	// --- Compose the status card in the framebuffer ---
	// White page, a one-pixel black border inset by 2 px (a frame on the very edge is
	// hidden by the panel bezel) and a black header bar across the top 24 rows.
	epd.Clear(display.IL0373White)                           // Fill framebuffer, (color)
	epd.DrawRect(2, 2, 100, 208, display.IL0373Black, false) // Draw rectangle, (x, y, w, h, color, fill)
	epd.DrawRect(3, 3, 98, 24, display.IL0373Black, true)    // Draw rectangle, (x, y, w, h, color, fill)

	// --- Bar chart of eight readings ---
	// Axes first, then one filled bar per value; the tallest bar is filled and the others are outlines.
	epd.DrawLine(10, 190, 95, 190, display.IL0373Black) // Draw line, (x0, y0, x1, y1, color)
	epd.DrawLine(10, 60, 10, 190, display.IL0373Black)  // Draw line, (x0, y0, x1, y1, color)
	values := []int{30, 55, 80, 45, 120, 90, 60, 100}
	peak := 0
	for _, v := range values {
		if v > peak {
			peak = v
		}
	}
	for i, v := range values {
		epd.DrawRect(14+i*10, 190-v, 8, v, display.IL0373Black, v == peak) // Draw rectangle, (x, y, w, h, color, fill)
	}

	// --- Refresh and time it ---
	// Show blocks for the whole DTM1/DTM2/DRF/POF sequence.
	start := time.Now()
	must(epd.Show()) // Refresh panel, () → error
	fmt.Println("refresh took", time.Since(start))

	// --- Save the image and power down ---
	// The panel keeps the picture without power; deep sleep stops the controller drawing current.
	must(epd.Sleep()) // Enter deep sleep, () → error
	must(conn.Close())
}

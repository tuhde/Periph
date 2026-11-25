//go:build linux && !tinygo

// UC8276C demo example — Linux host (Raspberry Pi).
//
// Waveshare 4.2inch e-Paper (B). Each refresh blocks for about 17 s.
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
	busy, err := connection.NewGpioInputPin(24) // Create BUSY_N pin, (line=24) → (*GpioInputPin, error)
	must(err)
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 4_000_000, dc, res, busy, connection.WireModeSimplex) // Create display connection, (bus, device, speedHz, dc, res, busy, wireMode)
	must(err)
	epd, err := display.NewUC8276CFull(conn, 400, 300, 30*time.Second) // Create UC8276C driver, (conn, width=400, height=300, busyTimeout) → (*UC8276CFull, error)
	must(err)

	// --- Compose the status card in the framebuffer ---
	// White page, a one-pixel black border inset by 4 px (a frame on the very edge is
	// hidden by the panel bezel) and a red header bar across the top 40 rows.
	epd.Clear(display.UC8276CWhite)                           // Fill framebuffer, (color)
	epd.DrawRect(4, 4, 392, 292, display.UC8276CBlack, false) // Draw rectangle, (x, y, w, h, color, fill)
	epd.DrawRect(5, 5, 390, 40, display.UC8276CRed, true)     // Draw rectangle, (x, y, w, h, color, fill)

	// --- Bar chart of eight readings ---
	// Axes first, then one filled bar per value; the tallest bar is red to flag the peak.
	epd.DrawLine(30, 270, 370, 270, display.UC8276CBlack) // Draw line, (x0, y0, x1, y1, color)
	epd.DrawLine(30, 70, 30, 270, display.UC8276CBlack)   // Draw line, (x0, y0, x1, y1, color)
	values := []int{30, 55, 80, 45, 120, 90, 60, 100}
	peak := 0
	for _, v := range values {
		if v > peak {
			peak = v
		}
	}
	for i, v := range values {
		color := display.UC8276CBlack
		if v == peak {
			color = display.UC8276CRed
		}
		epd.DrawRect(45+i*40, 270-v, 28, v, color, true) // Draw rectangle, (x, y, w, h, color, fill)
	}

	// --- Refresh and time it ---
	// Show blocks for the whole DTM1/DTM2/PON/DRF/POF sequence.
	start := time.Now()
	must(epd.Show()) // Refresh panel, () → error
	fmt.Println("refresh took", time.Since(start))

	// --- Save the image and power down ---
	// The panel keeps the picture without power; deep sleep stops the controller drawing current.
	must(epd.Sleep()) // Enter deep sleep, () → error
	must(conn.Close())
}

//go:build tinygo

// UC8276C demo example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 4.2inch e-Paper (B). Each refresh blocks for about 17 s.
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18 (SPI0 SCK), CS=GP17, DC=GP20, RST=GP21, BUSY=GP22.
package main

import (
	"fmt"
	"machine"
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
	must(machine.SPI0.Configure(machine.SPIConfig{
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Frequency: 1_000_000,
		Mode:      0,
	}))
	dc := connection.NewGpioOutputPin(machine.GP20)                                                                                // Create D/C# pin, (pin=GP20) → *GpioOutputPin
	res := connection.NewGpioOutputPin(machine.GP21)                                                                               // Create RES# pin, (pin=GP21) → *GpioOutputPin
	busy := connection.NewGpioInputPin(machine.GP22)                                                                               // Create BUSY_N pin, (pin=GP22) → *GpioInputPin
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, busy, connection.WireModeSimplex) // Create display connection, (spi, cs, dc, res, busy, wireMode)
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

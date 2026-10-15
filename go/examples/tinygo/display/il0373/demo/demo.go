//go:build tinygo

// IL0373 demo example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 2.13inch e-Paper (D). Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
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
	busy := connection.NewGpioInputPin(machine.GP22)                                                                               // Create BUSY pin, (pin=GP22) → *GpioInputPin
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, busy, connection.WireModeSimplex) // Create display connection, (spi, cs, dc, res, busy, wireMode)
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

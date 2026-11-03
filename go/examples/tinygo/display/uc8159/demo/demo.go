//go:build tinygo

// UC8159 demo example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 5.65inch e-Paper (F). Each refresh blocks for about 30 s.
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

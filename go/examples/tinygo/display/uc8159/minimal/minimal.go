//go:build tinygo

// UC8159 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 5.65inch e-Paper (F). Each refresh blocks for about 30 s.
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18 (SPI0 SCK), CS=GP17, DC=GP20, RST=GP21, BUSY=GP22.
package main

import (
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

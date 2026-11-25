//go:build tinygo

// IL0373 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 2.13inch e-Paper (D). Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
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

//go:build tinygo

// SSD1331 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 0.95inch RGB OLED. Eight colour bars and a white frame, sent once.
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18, CS=GP17, DC=GP20, RST=GP21 (no BUSY line).
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
		Frequency: 4_000_000,
		Mode:      0,
	}))
	dc := connection.NewGpioOutputPin(machine.GP20)                                                                               // Create D/C# pin, (pin=GP20) → *GpioOutputPin
	res := connection.NewGpioOutputPin(machine.GP21)                                                                              // Create RES# pin, (pin=GP21) → *GpioOutputPin
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, nil, connection.WireModeSimplex) // Create display connection, (spi, cs, dc, res, busy=nil, wireMode)
	must(err)
	oled, err := display.NewSSD1331Minimal(conn) // Create SSD1331 driver, (conn) → (*SSD1331Minimal, error)
	must(err)
	bars := []uint16{display.SSD1331White, display.SSD1331Yellow, display.SSD1331Cyan, display.SSD1331Green,
		display.SSD1331Magenta, display.SSD1331Red, display.SSD1331Blue, display.SSD1331Black}
	for y := 0; y < oled.Height(); y++ {
		for x := 0; x < oled.Width(); x++ {
			oled.SetPixel(x, y, bars[x/12]) // Set one pixel, (x, y, color)
		}
	}
	for x := 0; x < oled.Width(); x++ {
		oled.SetPixel(x, 0, display.SSD1331White)               // Set one pixel, (x, y, color)
		oled.SetPixel(x, oled.Height()-1, display.SSD1331White) // Set one pixel, (x, y, color)
	}
	must(oled.Show()) // Send framebuffer, () → error
	_ = time.Second
	must(conn.Close())
}

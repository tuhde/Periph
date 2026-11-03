//go:build linux && !tinygo

// SSD1331 minimal example — Linux host (Raspberry Pi).
//
// Waveshare 0.95inch RGB OLED. Eight colour bars and a white frame, sent once.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
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
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 4_000_000, dc, res, nil, connection.WireModeSimplex) // Create display connection, (bus, device, speedHz, dc, res, busy=nil, wireMode)
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

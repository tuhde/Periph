//go:build tinygo

// SSD1331 demo example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 0.95inch RGB OLED. A colour test card: eight colour bars over a gray ramp, a frame, a marker drawn with the controller's own rectangle command, then a slow scroll.
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
	oled, err := display.NewSSD1331Full(conn) // Create SSD1331 driver, (conn) → (*SSD1331Full, error)
	must(err)

	// --- Draw the test card into the framebuffer ---
	// Eight 12-pixel colour bars over a 26-row gray ramp show that all channels and the
	// 5-6-5 depth work; the one-pixel frame shows that the whole 96 x 64 area is addressed.
	bars := []uint16{display.SSD1331White, display.SSD1331Yellow, display.SSD1331Cyan, display.SSD1331Green,
		display.SSD1331Magenta, display.SSD1331Red, display.SSD1331Blue, display.SSD1331Black}
	oled.Clear(display.SSD1331Black) // Fill framebuffer, (color)
	for x := 0; x < 96; x++ {
		oled.DrawRect(x, 2, 1, 30, bars[x/12], true) // Draw rectangle, (x, y, w, h, color, fill)
		g := uint8(x * 255 / 95)
		oled.DrawRect(x, 34, 1, 26, display.SSD1331RGB(g, g, g), true) // Draw rectangle, (x, y, w, h, color, fill)
	}
	oled.DrawRect(0, 0, 96, 64, display.SSD1331White, false) // Draw rectangle, (x, y, w, h, color, fill)

	// --- Send it once ---
	must(oled.Show()) // Send framebuffer, () → error
	time.Sleep(3 * time.Second)

	// --- Animate with hardware drawing ---
	// The chip draws the marker straight into its RAM: no framebuffer transfer per step.
	for step := 0; step < 60; step++ {
		x := uint8(4 + (step*3)%80)
		must(oled.Show())                                                                // Send framebuffer, () → error
		must(oled.HwRect(x, 36, 10, 10, display.SSD1331White, display.SSD1331Red, true)) // Hardware rectangle, (x, y, w, h, lineColor, fillColor, fill) → error
		time.Sleep(50 * time.Millisecond)
	}

	// --- Scroll the test card ---
	// The chip scrolls its own RAM, one column every 6 frames.
	must(oled.Show())                 // Send framebuffer, () → error
	must(oled.Scroll(1, 0, 64, 0, 0)) // Scroll, (horizontal, startRow, rows, vertical, interval 0..3) → error
	must(conn.Close())
}

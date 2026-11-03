//go:build tinygo

// SSD1331 complete example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 0.95inch RGB OLED. Every Full method: software drawing, partial update, hardware line/rectangle/copy/dim/clear, scrolling, contrast, master current, flip, display modes, power save.
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
	sleep := func(ms int) { time.Sleep(time.Duration(ms) * time.Millisecond) }

	oled.Clear(display.SSD1331Black) // Fill framebuffer, (color)
	// sets every pixel; the display is unchanged until Show()
	oled.DrawRect(0, 0, 96, 64, display.SSD1331White, false) // Draw rectangle, (x, y, w, h, color, fill)
	oled.DrawRect(4, 4, 30, 20, display.SSD1331Red, true)    // Draw rectangle, (x, y, w, h, color, fill)
	oled.DrawLine(0, 0, 95, 63, display.SSD1331Green)        // Software line, (x0, y0, x1, y1, color)
	// Bresenham diagonal
	oled.DrawLine(0, 63, 95, 0, display.SSD1331Cyan) // Software line, (x0, y0, x1, y1, color)
	sprite := make([]uint16, 64)
	for i := range sprite {
		if ((i/8)+(i%8))&1 == 1 {
			sprite[i] = display.SSD1331Yellow
		} else {
			sprite[i] = display.SSD1331Magenta
		}
	}
	oled.Blit(60, 8, sprite, 8, 8) // Copy RGB565 bitmap, (x, y, pixels, w, h)
	// checkerboard into the framebuffer
	oled.SetPixel(48, 32, display.SSD1331White) // Set one pixel, (x, y, color)
	_ = oled.GetPixel(48, 32)                   // Read framebuffer pixel, (x, y) → uint16 RGB565
	must(oled.Show())                           // Send framebuffer, () → error
	// window 0..95 x 0..63 then 12288 bytes
	sleep(2000)

	oled.Clear(display.SSD1331Blue)                           // Fill framebuffer, (color)
	oled.DrawRect(20, 16, 56, 32, display.SSD1331White, true) // Draw rectangle, (x, y, w, h, color, fill)
	must(oled.ShowRegion(16, 12, 64, 40))                     // Send region, (x, y, w, h) → error
	// only the rows of the window are transferred
	sleep(2000)

	must(oled.HwClear(0, 0, 95, 63)) // Clear window in display RAM, (x0, y0, x1, y1) → error
	// chip clears its own RAM, framebuffer untouched
	must(oled.HwRect(10, 10, 76, 44, display.SSD1331Yellow, display.SSD1331Red, true)) // Hardware rectangle, (x, y, w, h, lineColor, fillColor, fill) → error
	// outline and fill drawn by the chip
	must(oled.HwLine(0, 0, 95, 63, display.SSD1331White)) // Hardware line, (x0, y0, x1, y1, color) → error
	sleep(2000)
	must(oled.HwCopy(10, 10, 40, 40, 50, 20)) // Hardware copy, (x0, y0, x1, y1, x, y) → error
	sleep(2000)
	must(oled.HwDim(0, 0, 47, 63)) // Hardware dim window, (x0, y0, x1, y1) → error
	sleep(2000)

	must(oled.SetDisplayMode(display.SSD1331ModeInverse)) // Display mode, (mode) → error
	sleep(1500)
	must(oled.SetDisplayMode(display.SSD1331ModeAllOn)) // Display mode, (mode) → error
	sleep(1000)
	must(oled.SetDisplayMode(display.SSD1331ModeAllOff)) // Display mode, (mode) → error
	sleep(1000)
	must(oled.SetDisplayMode(display.SSD1331ModeNormal)) // Display mode, (mode) → error

	must(oled.SetContrast(0x30, 0x30, 0x30)) // Channel contrast, (a, b, c) 0..255
	sleep(1000)
	must(oled.SetContrast(0xFF, 0xFF, 0xFF)) // Channel contrast, (a, b, c) 0..255
	must(oled.SetMasterCurrent(4))           // Master current, (n 1..16) n/16
	sleep(1000)
	must(oled.SetMasterCurrent(7)) // Master current, (n 1..16) n/16

	must(oled.SetFlip(false, true)) // Mirror, (horizontal, vertical) → error
	must(oled.Show())               // Send framebuffer, () → error
	sleep(1500)
	must(oled.SetFlip(true, true)) // Mirror, (horizontal, vertical) → error
	must(oled.Show())              // Send framebuffer, () → error
	sleep(1500)

	must(oled.Scroll(1, 0, 64, 0, 0)) // Scroll, (horizontal, startRow, rows, vertical, interval 0..3) → error
	// continuous scroll one column per 6 frames
	sleep(4000)
	must(oled.ScrollStop()) // Stop scrolling, () → error

	must(oled.DimMode(0x10, 0x10, 0x10, 0x10)) // Dim mode, (a, b, c, precharge 0..31) → error
	sleep(1500)
	must(oled.SetPowerSave(true)) // Power save, (on) → error
	must(oled.DisplayOff())       // Display off, () → error
	must(conn.Close())
}

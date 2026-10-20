//go:build tinygo

// UC1611s complete example (4-wire SPI) — TinyGo / Raspberry Pi Pico W.
//
// EA DOGXL240-7 (240 x 128, 3.3 V only). Wiring: SI=GP19 (SPI0 TX), SCK=GP18, CS0=GP17, CD=GP20, RST=GP21, VDD = 3V3;
// module pins BM0 = D13 = VSS (4-wire SPI). Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD.
// Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, partial display, RAM screens, sleep, raw access, reset (SPI has no read-back).
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	if err := machine.SPI0.Configure(machine.SPIConfig{
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Frequency: 4_000_000,
		Mode:      0,
	}); err != nil {
		panic(err)
	}
	dc := connection.NewGpioOutputPin(machine.GP20)                                                                               // Create CD (D/C) pin, (pin=GP20) → *GpioOutputPin
	res := connection.NewGpioOutputPin(machine.GP21)                                                                              // Create RST pin, (pin=GP21) → *GpioOutputPin
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, nil, connection.WireModeSimplex) // Create display connection, (spi, cs, dc, res, busy=nil, wireMode)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	lcd, err := display.NewUC1611sSpiFull(conn, 0, 0, false, 0x8F) // Create UC1611s driver, (conn, width=240, height=128, topView=false, contrast=0x8F) → (*UC1611sFull, error)
	if err != nil {
		panic(err)
	}

	// Framebuffer drawing: everything is composed in RAM and sent with Show().
	lcd.Clear() // Clear framebuffer, ()
	// zeroes every pixel of the framebuffer; the panel is unchanged until Show()
	lcd.Fill(true) // Fill framebuffer, (on)
	// sets (or clears) every pixel
	lcd.Fill(false)                           // Fill framebuffer, (on)
	lcd.DrawRect(0, 0, 240, 128, true, false) // Draw rectangle, (x, y, w, h, on, fill)
	// one-pixel outline around the whole panel
	lcd.DrawLine(0, 0, 239, 127, true) // Draw line, (x0, y0, x1, y1, on)
	// Bresenham line into the framebuffer
	lcd.DrawLine(0, 127, 239, 0, true)       // Draw line, (x0, y0, x1, y1, on)
	lcd.DrawRect(20, 20, 40, 30, true, true) // Draw rectangle, (x, y, w, h, on, fill)
	// filled rectangle
	lcd.DrawRect(30, 28, 20, 14, false, true) // Draw rectangle, (x, y, w, h, on, fill)
	// on=false clears pixels: a hole in the filled rectangle
	lcd.SetPixel(120, 64, true)  // Set pixel, (x, y, on)
	lit := lcd.GetPixel(120, 64) // Read framebuffer pixel, (x, y) → bool
	// reads the framebuffer, not the panel
	icon := []byte{0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00}
	if err := lcd.Blit(180, 16, icon, 2); err != nil { // Copy bitmap, (x, y multiple of 8, columns, heightPages) → error
		// two page rows of 8 columns, bit 0 = top pixel
		panic(err)
	}
	if err := lcd.Show(); err != nil { // Send framebuffer to the display, () → error
		// per page: page + column address, then 240 data bytes in one transfer
		panic(err)
	}
	time.Sleep(2 * time.Second)
	if err := lcd.ShowPage(2); err != nil { // Send one framebuffer page, (page 0..15) → error
		// cheaper partial update of 8 rows
		panic(err)
	}

	// Contrast and display modes.
	if err := lcd.SetContrast(0x70); err != nil { // Set contrast, (value 0..255) → error
		// writes the potentiometer PM; lower = lighter
		panic(err)
	}
	time.Sleep(800 * time.Millisecond)
	if err := lcd.SetContrast(0x8F); err != nil { // Set contrast, (value 0..255) → error
		// 0x8F is the DOG example value
		panic(err)
	}
	if err := lcd.SetInvert(true); err != nil { // Invert display, (on) → error
		// bit-wise inversion of what is displayed; RAM unchanged
		panic(err)
	}
	time.Sleep(800 * time.Millisecond)
	if err := lcd.SetInvert(false); err != nil { // Invert display, (on) → error
		panic(err)
	}
	if err := lcd.SetAllOn(true); err != nil { // Light all pixels, (on) → error
		// test mode, RAM unchanged
		panic(err)
	}
	time.Sleep(800 * time.Millisecond)
	if err := lcd.SetAllOn(false); err != nil { // Light all pixels, (on) → error
		panic(err)
	}
	if err := lcd.SetFlip(false, true); err != nil { // Mirror display, (mirrorX, mirrorY) → error
		// MY = 1: rows mirrored at once; MX is applied to RAM writes
		panic(err)
	}
	time.Sleep(800 * time.Millisecond)
	if err := lcd.SetFlip(true, false); err != nil { // Mirror display, (mirrorX, mirrorY) → error
		// back to the bottom-view default
		panic(err)
	}
	if err := lcd.Show(); err != nil { // Send framebuffer to the display, () → error
		panic(err)
	}

	// Hardware scroll with a fixed header.
	if err := lcd.SetFixedLines(2); err != nil { // Set fixed lines, (lines 0..15) → error
		// the top 2 x 2 = 4 rows stay put
		panic(err)
	}
	for line := uint8(0); line <= 32; line += 4 {
		if err := lcd.SetScrollLine(line); err != nil { // Scroll the image up, (line 0..159 - 2 * fixed) → error
			// rows enter from the bottom as the image moves up
			panic(err)
		}
		time.Sleep(120 * time.Millisecond)
	}
	if err := lcd.SetScrollLine(0); err != nil { // Scroll the image up, (line 0..159 - 2 * fixed) → error
		panic(err)
	}
	if err := lcd.SetFixedLines(0); err != nil { // Set fixed lines, (lines 0..15) → error
		panic(err)
	}

	// LCD drive tuning (the DOG defaults are already sent at init).
	if err := lcd.SetBiasRatio(2); err != nil { // Set bias ratio, (ratio 0..3) → error
		// 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12
		panic(err)
	}
	if err := lcd.SetTemperatureCompensation(1); err != nil { // Set temperature compensation, (curve 0..3) → error
		// 1 = -0.10 %/degC, the DOG example value
		panic(err)
	}
	if err := lcd.SetLineRate(3); err != nil { // Set line rate, (rate 0..3) → error
		// 3 = 9.4 kilo-lines per second in On/Off mode
		panic(err)
	}
	if err := lcd.SetPump(true); err != nil { // Select VLCD source, (internal) → error
		// internal 11x charge pump
		panic(err)
	}
	if err := lcd.SetPanelLoading(true); err != nil { // Set panel loading, (high) → error
		// 33..55 nF panel capacitance
		panic(err)
	}
	if err := lcd.SetNLineInversion(0, false); err != nil { // Set N-line inversion, (lines 0..63, xor) → error
		// 0 = off
		panic(err)
	}
	if err := lcd.SetIsolationClock(1, 0); err != nil { // Set isolation clock, (front 0..15, back 0..15) → error
		// the reset values
		panic(err)
	}

	// Partial display: scan only the middle 64 rows.
	if err := lcd.SetPartialDisplay(true, 32, 95); err != nil { // Set partial display, (on, start, end) → error
		// rows outside 32..95 are not scanned and save power
		panic(err)
	}
	time.Sleep(1500 * time.Millisecond)
	if err := lcd.SetPartialDisplay(false, 0, -1); err != nil { // Set partial display, (on, start, end) → error
		// back to the full panel
		panic(err)
	}

	// Four RAM screens: draw into screen 1, then switch to it.
	lcd.SetWritePattern(1) // Select RAM screen to write, (pattern 0..3)
	// Show() now writes RAM screen 1
	lcd.Fill(false)                           // Fill framebuffer, (on)
	lcd.DrawRect(40, 30, 160, 68, true, true) // Draw rectangle, (x, y, w, h, on, fill)
	if err := lcd.Show(); err != nil {        // Send framebuffer to the display, () → error
		// screen 0 is still displayed
		panic(err)
	}
	if err := lcd.SetDisplayPattern(1); err != nil { // Select displayed RAM screen, (pattern 0..3) → error
		// the display now shows RAM screen 1
		panic(err)
	}
	time.Sleep(1500 * time.Millisecond)
	if err := lcd.SetDisplayPattern(0); err != nil { // Select displayed RAM screen, (pattern 0..3) → error
		panic(err)
	}
	lcd.SetWritePattern(0) // Select RAM screen to write, (pattern 0..3)

	// Sleep, raw access, reset.
	if err := lcd.Sleep(); err != nil { // Enter sleep, () → error
		// drivers, pump and clock stop; registers and RAM are kept
		panic(err)
	}
	time.Sleep(time.Second)
	if err := lcd.Wake(); err != nil { // Leave sleep, () → error
		// polls Status1.DE while the chip restores VLCD
		panic(err)
	}
	if err := lcd.SendCommand(0xE3); err != nil { // Send raw command bytes, (bytes...) → error
		// 0xE3 = NOP
		panic(err)
	}
	if err := lcd.SendData(make([]byte, 8)); err != nil { // Send raw RAM data, (bytes) → error
		// written at the current RAM address
		panic(err)
	}
	if err := lcd.Reset(); err != nil { // System Reset and reconfigure, () → error
		// registers back to the DOG settings with the cached contrast and mapping; RAM is kept
		panic(err)
	}
	_ = lit
	fmt.Println("done")
}

//go:build linux && !tinygo

// SSD1305 complete example — Linux host.
//
// 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) on /dev/i2c-N. Wiring: SDA=GPIO2, SCL=GPIO3 (Raspberry Pi), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump);
// address 0x3C (SA0 = low) or 0x3D (SA0 = high). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
package main

import (
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x3C"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x3C) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	lcd, err := display.NewSSD1305Full(conn, 0, 0, 4) // Create SSD1305 driver, (conn, width=128, height=64, columnOffset=4) → (*SSD1305Full, error)
	if err != nil {
		panic(err)
	}

	// Framebuffer drawing: everything is composed in RAM and sent with Show().
	lcd.Clear() // Clear framebuffer, ()
	// zeroes every pixel of the framebuffer; the panel is unchanged until Show()
	lcd.Fill(true) // Fill framebuffer, (on)
	// sets (or clears) every pixel
	lcd.Fill(false)                          // Fill framebuffer, (on)
	lcd.DrawRect(0, 0, 128, 64, true, false) // Draw rectangle, (x, y, w, h, on, fill)
	// one-pixel outline around the whole panel
	lcd.DrawLine(0, 0, 127, 63, true) // Draw line, (x0, y0, x1, y1, on)
	// Bresenham line into the framebuffer
	lcd.DrawLine(0, 63, 127, 0, true)        // Draw line, (x0, y0, x1, y1, on)
	lcd.DrawRect(10, 10, 30, 20, true, true) // Draw rectangle, (x, y, w, h, on, fill)
	// filled rectangle
	lcd.DrawRect(18, 16, 14, 8, false, true) // Draw rectangle, (x, y, w, h, on, fill)
	// on=false clears pixels: a hole in the filled rectangle
	lcd.SetPixel(64, 32, true)  // Set pixel, (x, y, on)
	lit := lcd.GetPixel(64, 32) // Read framebuffer pixel, (x, y) → bool
	// reads the framebuffer, not the panel
	icon := []byte{0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00}
	if err := lcd.Blit(100, 16, icon, 2); err != nil { // Copy bitmap, (x, y multiple of 8, columns, heightPages) → error
		// two page rows of 8 columns, bit 0 = top pixel
		panic(err)
	}
	if err := lcd.Show(); err != nil { // Send framebuffer to the display, () → error
		// per page: page + column command, then 128 data bytes in 31-byte chunks
		panic(err)
	}
	time.Sleep(2 * time.Second)
	if err := lcd.ShowPage(2); err != nil { // Send one framebuffer page, (page 0..7) → error
		// cheaper partial update of 8 rows
		panic(err)
	}

	// Contrast and display modes.
	if err := lcd.SetContrast(0x20); err != nil { // Set contrast, (value 0..255) → error
		// segment drive current; reset value is 0x80
		panic(err)
	}
	time.Sleep(800 * time.Millisecond)
	if err := lcd.SetContrast(0x80); err != nil { // Set contrast, (value 0..255) → error
		panic(err)
	}
	if err := lcd.SetInvert(true); err != nil { // Invert display, (on) → error
		// RAM 0 = pixel on; RAM is unchanged
		panic(err)
	}
	time.Sleep(800 * time.Millisecond)
	if err := lcd.SetInvert(false); err != nil { // Invert display, (on) → error
		panic(err)
	}
	if err := lcd.SetAllOn(true); err != nil { // Force all pixels on, (on) → error
		// test mode, RAM is kept
		panic(err)
	}
	time.Sleep(800 * time.Millisecond)
	if err := lcd.SetAllOn(false); err != nil { // Force all pixels on, (on) → error
		panic(err)
	}
	if err := lcd.SetFlip(true, true); err != nil { // Mirror display, (horizontal, vertical) → error
		// segment re-map and COM scan direction; framebuffer unchanged
		panic(err)
	}
	time.Sleep(1500 * time.Millisecond)
	if err := lcd.SetFlip(false, false); err != nil { // Mirror display, (horizontal, vertical) → error
		panic(err)
	}

	// Hardware vertical scroll and drive tuning.
	for line := uint8(0); line < 64; line += 2 {
		if err := lcd.SetStartLine(line); err != nil { // Scroll the image up, (line 0..63) → error
			// RAM line shown at COM0; the image wraps around
			panic(err)
		}
		time.Sleep(30 * time.Millisecond)
	}
	if err := lcd.SetStartLine(0); err != nil { // Scroll the image up, (line 0..63) → error
		panic(err)
	}
	if err := lcd.SetDisplayOffset(0); err != nil { // Set COM offset, (offset 0..63) → error
		// shifts the COM mapping
		panic(err)
	}
	if err := lcd.SetMultiplex(64); err != nil { // Set multiplex ratio, (ratio 16..64) → error
		// number of driven rows
		panic(err)
	}
	if err := lcd.SetClock(1, 7); err != nil { // Set clock, (divide 1..16, osc 0..15) → error
		// divide ratio 1 at the reset oscillator setting
		panic(err)
	}
	if err := lcd.SetPrecharge(2, 2); err != nil { // Set pre-charge, (phase1 1..15, phase2 1..15) → error
		// pre-charge and discharge periods in display clocks
		panic(err)
	}
	if err := lcd.SetComConfig(true, false); err != nil { // Set COM pin configuration, (alternative, remap) → error
		// alternative is the reset value and right for 128 x 64 modules
		panic(err)
	}
	if err := lcd.SetVcomh(0x34); err != nil { // Set VCOMH deselect level, (value 0x00..0x3C) → error
		// 0x34 (about 0.77 x VCC) is the reset value
		panic(err)
	}
	if err := lcd.SetDimMode(0x40, 0x40); err != nil { // Configure dim levels, (contrast 0..255, brightness 0..255) → error
		// used while dim mode is on
		panic(err)
	}
	if err := lcd.SetDim(true); err != nil { // Dim mode, (on) → error
		// 0xAC display on in dim mode
		panic(err)
	}
	time.Sleep(time.Second)
	if err := lcd.SetDim(false); err != nil { // Dim mode, (on) → error
		panic(err)
	}
	if err := lcd.SetBrightness(0x80); err != nil { // Set colour-bank brightness, (value 0..255) → error
		// no visible effect in monochrome mode
		panic(err)
	}
	if err := lcd.SetAreaColor(false, false); err != nil { // Set area-colour mode, (enable, lowPower) → error
		// keep disabled on monochrome panels
		panic(err)
	}
	if err := lcd.SetLUT(49, 63, 63, 63); err != nil { // Set pulse widths, (bank0, a, b, c each 31..63) → error
		// reset values
		panic(err)
	}
	if err := lcd.SetBankColors(make([]byte, 4), make([]byte, 4)); err != nil { // Assign bank colours, (banks1to16, banks17to32 or nil) → error
		// all banks colour A
		panic(err)
	}
	if err := lcd.SetAddressingMode(display.SSD1305AddrHorizontal); err != nil { // Select addressing mode, (mode) → error
		// Show then streams the frame in one transfer
		panic(err)
	}
	if err := lcd.Show(); err != nil { // Update display, () → error
		panic(err)
	}
	if err := lcd.SetAddressingMode(display.SSD1305AddrPage); err != nil { // Select addressing mode, (mode) → error
		panic(err)
	}

	// Hardware scrolling.
	if err := lcd.SetScrollHorizontal(true, 0, 7, 0, 1); err != nil { // Set horizontal scroll, (right, startPage, endPage, interval 0..6, columns) → error
		// interval index 0 = 6 frames per step
		panic(err)
	}
	if err := lcd.ScrollStart(); err != nil { // Start scrolling, () → error
		panic(err)
	}
	time.Sleep(2 * time.Second)
	if err := lcd.ScrollStop(); err != nil { // Stop scrolling, () → error
		// deactivates and rewrites the RAM from the framebuffer
		panic(err)
	}
	if err := lcd.SetScrollArea(0, 64); err != nil { // Set vertical scroll area, (topFixed 0..63, scrollRows 1..64) → error
		panic(err)
	}
	if err := lcd.SetScrollVertical(true, 0, 7, 0, 1, 1); err != nil { // Set vertical+horizontal scroll, (right, startPage, endPage, interval, columns, rows 1..63) → error
		panic(err)
	}
	if err := lcd.ScrollStart(); err != nil { // Start scrolling, () → error
		panic(err)
	}
	time.Sleep(2 * time.Second)
	if err := lcd.ScrollStop(); err != nil { // Stop scrolling, () → error
		panic(err)
	}

	// Reads (I2C only) and raw commands.
	status, err := lcd.ReadStatus() // Read status, () → (byte, error)
	// bit 6 = 1 when the display is off
	if err != nil {
		panic(err)
	}
	ram, err := lcd.ReadPage(0, 2, 8) // Read display RAM, (page 0..7, column, n) → ([]byte, error)
	// sets the address and discards the dummy byte
	if err != nil {
		panic(err)
	}
	if err := lcd.SendCommand(0xE3); err != nil { // Send raw command stream, (bytes...) → error
		// NOP
		panic(err)
	}

	// Power.
	if err := lcd.SetDisplay(false); err != nil { // Display on/off, (on) → error
		// RAM and settings are kept
		panic(err)
	}
	time.Sleep(time.Second)
	if err := lcd.SetDisplay(true); err != nil { // Display on/off, (on) → error
		panic(err)
	}
	if err := lcd.Sleep(); err != nil { // Enter sleep, () → error
		// display off; switch VCC off afterwards
		panic(err)
	}
	time.Sleep(time.Second)
	if err := lcd.Wake(); err != nil { // Leave sleep, () → error
		// display on and a 100 ms wait (tAF)
		panic(err)
	}
	println("status", status, "lit", lit, "ram", len(ram))
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

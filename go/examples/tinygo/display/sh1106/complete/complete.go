//go:build tinygo

// SH1106 complete example — TinyGo / Raspberry Pi Pico W.
//
// 1.3" 128 x 64 OLED module (SH1106, RAM columns 2..129) on machine.I2C1 with GP4 = SDA and GP5 = SCL, VCC = 3V3;
// address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x3C, nil, nil) // Create I2C connection, (i2c, addr=0x3C) → (*I2CConnection)
	lcd, err := display.NewSH1106Full(conn, 0, 0, 2, true)   // Create SH1106 driver, (conn, width=128, height=64, columnOffset=2, internalDCDC=true) → (*SH1106Full, error)
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
	if err := lcd.SetMultiplex(64); err != nil { // Set multiplex ratio, (ratio 1..64) → error
		// number of driven rows
		panic(err)
	}
	if err := lcd.SetClock(1, 5); err != nil { // Set clock, (divide 1..16, osc 0..15) → error
		// divide ratio 1 at the nominal oscillator frequency
		panic(err)
	}
	if err := lcd.SetPrecharge(2, 2); err != nil { // Set pre-charge, (precharge 1..15, discharge 1..15) → error
		// pre-charge and discharge periods in display clocks
		panic(err)
	}
	if err := lcd.SetComConfig(true); err != nil { // Set COM pin configuration, (alternative) → error
		// alternative (0x12) is the reset value and right for 128 x 64 modules
		panic(err)
	}
	if err := lcd.SetVcomDeselect(0x35); err != nil { // Set VCOM deselect level, (value 0..255) → error
		panic(err)
	}
	if err := lcd.SetPumpVoltage(0); err != nil { // Set pump voltage, (level 0..3) → error
		// 0 = 7.4 V, 1 = 8.0 V, 2 = 8.4 V, 3 = 9.0 V
		panic(err)
	}

	// Reads (I2C only) and raw commands.
	status, err := lcd.ReadStatus() // Read status, () → (byte, error)
	// bit 7 BUSY, bit 6 = 1 when the display is off
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
		// display off; the DC-DC stops
		panic(err)
	}
	time.Sleep(time.Second)
	if err := lcd.Wake(); err != nil { // Leave sleep, () → error
		// display on and a 100 ms wait for VPP
		panic(err)
	}
	if err := lcd.SetDCDC(true); err != nil { // Switch the internal DC-DC, (on) → error
		// issue while the display is off; true = 0x8B, false = 0x8A for an external VPP
		panic(err)
	}
	println("status", status, "lit", lit, "ram", len(ram))
}

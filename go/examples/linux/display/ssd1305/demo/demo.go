//go:build linux && !tinygo

// SSD1305 demo example — Linux host.
//
// 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) on /dev/i2c-N. Wiring: SDA=GPIO2, SCL=GPIO3 (Raspberry Pi), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump);
// address 0x3C (SA0 = low) or 0x3D (SA0 = high). Demo: a bouncing ball inside a border, a contrast fade, inverse flashes, hardware scroll and sleep.
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

	// --- Draw the border once ---
	// A one-pixel border around the 128 x 64 panel; the ball bounces inside it.
	lcd.Clear()                              // Clear framebuffer, ()
	lcd.DrawRect(0, 0, 128, 64, true, false) // Draw rectangle, (x, y, w, h, on, fill)
	if err := lcd.Show(); err != nil {       // Send framebuffer to the display, () → error
		panic(err)
	}

	// --- Bounce a 6 x 6 ball at about 20 frames per second ---
	// The ball is erased and redrawn in the framebuffer, then the frame is sent with Show().
	bx, by, dx, dy := 20, 12, 3, 2
	for frame := 0; frame < 200; frame++ {
		lcd.DrawRect(bx, by, 6, 6, false, true) // Draw rectangle, (x, y, w, h, on, fill)
		bx += dx
		by += dy
		if bx < 2 || bx > 120 {
			dx = -dx
			bx += 2 * dx
		}
		if by < 2 || by > 56 {
			dy = -dy
			by += 2 * dy
		}
		lcd.DrawRect(bx, by, 6, 6, true, true) // Draw rectangle, (x, y, w, h, on, fill)
		if err := lcd.Show(); err != nil {     // Send framebuffer to the display, () → error
			panic(err)
		}
		time.Sleep(50 * time.Millisecond)
	}

	// --- Fade the contrast down and back up ---
	// 255 -> 0 -> 255 in steps of 5 shows the full range of the segment drive current.
	for v := 255; v >= 0; v -= 5 {
		if err := lcd.SetContrast(uint8(v)); err != nil { // Set contrast, (value 0..255) → error
			panic(err)
		}
		time.Sleep(20 * time.Millisecond)
	}
	for v := 0; v <= 255; v += 5 {
		if err := lcd.SetContrast(uint8(v)); err != nil { // Set contrast, (value 0..255) → error
			panic(err)
		}
		time.Sleep(20 * time.Millisecond)
	}

	// --- Flash the inverse display twice ---
	for i := 0; i < 2; i++ {
		if err := lcd.SetInvert(true); err != nil { // Invert display, (on) → error
			panic(err)
		}
		time.Sleep(400 * time.Millisecond)
		if err := lcd.SetInvert(false); err != nil { // Invert display, (on) → error
			panic(err)
		}
		time.Sleep(400 * time.Millisecond)
	}

	// --- Dim mode ---
	// Dim mode lowers contrast and colour-bank brightness to the levels stored with SetDimMode.
	if err := lcd.SetDimMode(0x10, 0x10); err != nil { // Configure dim levels, (contrast 0..255, brightness 0..255) → error
		panic(err)
	}
	if err := lcd.SetDim(true); err != nil { // Dim mode, (on) → error
		panic(err)
	}
	time.Sleep(2 * time.Second)
	if err := lcd.SetDim(false); err != nil { // Dim mode, (on) → error
		panic(err)
	}

	// --- Hardware scroll ---
	// The scroll engine moves all eight pages right in the controller without re-sending any data;
	// stopping rewrites the RAM from the framebuffer as the datasheet requires.
	if err := lcd.SetScrollHorizontal(true, 0, 7, 0, 1); err != nil { // Set horizontal scroll, (right, startPage, endPage, interval 0..6, columns) → error
		panic(err)
	}
	if err := lcd.ScrollStart(); err != nil { // Start scrolling, () → error
		panic(err)
	}
	time.Sleep(5 * time.Second)
	if err := lcd.ScrollStop(); err != nil { // Stop scrolling, () → error
		panic(err)
	}

	// --- Power down ---
	// Sleep turns the display off (switch VCC off afterwards); RAM and registers are kept.
	if err := lcd.Sleep(); err != nil { // Enter sleep, () → error
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

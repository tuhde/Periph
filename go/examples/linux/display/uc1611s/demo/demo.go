//go:build linux && !tinygo

// UC1611s demo example — Linux host.
//
// EA DOGXL240-7 (240 x 128, 3.3 V only) on /dev/i2c-N. Wiring: SDA=GPIO2, SCL=GPIO3 (Raspberry Pi), VDD = 3V3;
// module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39. Capacitors: 4 x 4.7 uF/5 V (VA0, VA1,
// VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.
package main

import (
	"fmt"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	if err != nil {
		panic(err)
	}

	command, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create command connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer command.Close()
	data, err := connection.NewI2CConnection(bus, uint8(addr)+1, nil, nil) // Create data connection, (bus=1, addr=0x39) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer data.Close()

	lcd, err := display.NewUC1611sFull(command, data, 0, 0, false, 0x8F) // Create UC1611s driver, (command, data, width=240, height=128, topView=false, contrast=0x8F) → (*UC1611sFull, error)
	if err != nil {
		panic(err)
	}

	// --- Draw the frame once ---
	// A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
	lcd.Clear()                               // Clear framebuffer, ()
	lcd.DrawRect(0, 0, 240, 128, true, false) // Draw rectangle, (x, y, w, h, on, fill)
	if err := lcd.Show(); err != nil {        // Send framebuffer to the display, () → error
		panic(err)
	}

	// --- Bounce an 8 x 8 ball with partial page updates ---
	// Only the page rows the ball touches are re-sent, so the animation runs at about
	// 20 frames per second even at 400 kHz, instead of the 8 of a full-frame Show().
	bx, by, dx, dy := 20, 12, 3, 2
	for frame := 0; frame < 200; frame++ {
		oldy := by
		lcd.DrawRect(bx, by, 8, 8, false, true) // Draw rectangle, (x, y, w, h, on, fill)
		bx += dx
		by += dy
		if bx < 2 || bx > 229 {
			dx = -dx
			bx += 2 * dx
		}
		if by < 2 || by > 118 {
			dy = -dy
			by += 2 * dy
		}
		lcd.DrawRect(bx, by, 8, 8, true, true) // Draw rectangle, (x, y, w, h, on, fill)
		first, last := oldy/8, (by+7)/8
		if by < oldy {
			first, last = by/8, (oldy+7)/8
		}
		for page := first; page <= last; page++ {
			if err := lcd.ShowPage(page); err != nil { // Send one framebuffer page, (page 0..15) → error
				panic(err)
			}
		}
		time.Sleep(20 * time.Millisecond)
	}

	// --- Contrast sweep around the working point ---
	// The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
	for pm := 0x8F; pm >= 0x70; pm -= 2 {
		if err := lcd.SetContrast(uint8(pm)); err != nil { // Set contrast, (value 0..255) → error
			panic(err)
		}
		time.Sleep(40 * time.Millisecond)
	}
	for pm := 0x70; pm <= 0x8F; pm += 2 {
		if err := lcd.SetContrast(uint8(pm)); err != nil { // Set contrast, (value 0..255) → error
			panic(err)
		}
		time.Sleep(40 * time.Millisecond)
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

	// --- Hardware scroll ---
	// The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
	// because RAM rows 128..159 are empty.
	for line := 0; line <= 32; line++ {
		if err := lcd.SetScrollLine(uint8(line)); err != nil { // Scroll the image up, (line 0..159 - 2 * fixed) → error
			panic(err)
		}
		time.Sleep(40 * time.Millisecond)
	}
	for line := 32; line >= 0; line-- {
		if err := lcd.SetScrollLine(uint8(line)); err != nil { // Scroll the image up, (line 0..159 - 2 * fixed) → error
			panic(err)
		}
		time.Sleep(40 * time.Millisecond)
	}

	// --- Second screen in RAM ---
	// The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
	lcd.SetWritePattern(1)             // Select RAM screen to write, (pattern 0..3)
	lcd.Fill(false)                    // Fill framebuffer, (on)
	lcd.DrawLine(0, 0, 239, 127, true) // Draw line, (x0, y0, x1, y1, on)
	lcd.DrawLine(0, 127, 239, 0, true) // Draw line, (x0, y0, x1, y1, on)
	if err := lcd.Show(); err != nil { // Send framebuffer to the display, () → error
		panic(err)
	}
	if err := lcd.SetDisplayPattern(1); err != nil { // Select displayed RAM screen, (pattern 0..3) → error
		panic(err)
	}
	time.Sleep(2 * time.Second)
	if err := lcd.SetDisplayPattern(0); err != nil { // Select displayed RAM screen, (pattern 0..3) → error
		panic(err)
	}
	lcd.SetWritePattern(0) // Select RAM screen to write, (pattern 0..3)
	time.Sleep(1500 * time.Millisecond)

	// --- Power down ---
	// Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
	if err := lcd.Sleep(); err != nil { // Enter sleep, () → error
		panic(err)
	}
	fmt.Printf("done (I2C retries %d, unrecovered %d)\n", lcd.RetryCount(), lcd.ErrorCount())
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

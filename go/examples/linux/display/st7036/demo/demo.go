//go:build linux && !tinygo

// ST7036 demo example — Linux host (Raspberry Pi).
//
// EA DOGM162W-A (2 x 16, 3.3 V). A two-line status display: a title line and a bar graph built from five custom characters that fills, then a contrast sweep.
// Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25 (C/D), RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
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
	dc, err := connection.NewGpioOutputPin(25) // Create RS (C/D) pin, (line=25) → (*GpioOutputPin, error)
	must(err)
	res, err := connection.NewGpioOutputPin(17) // Create RESET pin, (line=17) → (*GpioOutputPin, error)
	must(err)
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 1_000_000, dc, res, nil, connection.WireModeSimplex) // Create display connection, (bus, device, speedHz, dc, res, busy=nil, wireMode)
	must(err)
	lcd, err := display.NewST7036Full(conn, 2, 0, display.ST7036Supply3V3, -1) // Create ST7036 driver, (conn, lines=2, columns=0 default, supply=3V3, contrast=-1 preset) → (*ST7036Full, error)
	must(err)

	// --- Define the bar-graph glyphs ---
	// Custom characters 0..4 are columns of 1..5 filled pixel columns, so a 16-character bar
	// can show 80 steps with the eight CGRAM slots.
	for g := uint(0); g < 5; g++ {
		pattern := uint8(0x1F<<(4-g)) & 0x1F
		rows := [8]uint8{0, pattern, pattern, pattern, pattern, pattern, pattern, 0}
		must(lcd.CreateChar(uint8(g), rows)) // Define user character, (index 0..7, rows[8]) → error
	}

	// --- Static title line ---
	must(lcd.Clear())                        // Clear display, () → error
	must(lcd.PrintAt(0, 0, "Periph ST7036")) // Write text, (col, row, text) → error

	// --- Animate a progress bar on line 2 ---
	// Each step fills 1/5 of a character cell; a full bar is 16 x 5 = 80 steps.
	for step := 0; step <= 80; step++ {
		must(lcd.SetCursor(0, 1)) // Move cursor, (col, row) → error
		for col := 0; col < 16; col++ {
			fill := step - col*5 // pixel columns of this cell that are filled
			switch {
			case fill >= 5:
				must(lcd.WriteChar(4)) // Write raw character code, (code) → error
			case fill > 0:
				must(lcd.WriteChar(uint8(fill - 1))) // Write raw character code, (code) → error
			default:
				must(lcd.WriteChar(' ')) // Write raw character code, (code) → error
			}
		}
		time.Sleep(40 * time.Millisecond)
	}
	time.Sleep(time.Second)

	// --- Contrast sweep ---
	// The contrast is set once in normal use; sweeping it shows the usable range of the module.
	must(lcd.PrintAt(0, 0, "Contrast sweep ")) // Write text, (col, row, text) → error
	for c := uint8(0x10); c <= 0x30; c++ {
		must(lcd.SetContrast(c)) // Set contrast, (value 0..63) → error
		time.Sleep(80 * time.Millisecond)
	}
	must(lcd.SetContrast(0x18))                 // Set contrast, (value 0..63) → error
	must(lcd.PrintAt(0, 0, "Periph ST7036   ")) // Write text, (col, row, text) → error
	must(conn.Close())
}

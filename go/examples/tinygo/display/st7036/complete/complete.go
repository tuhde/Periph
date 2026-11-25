//go:build tinygo

// ST7036 complete example — TinyGo / Raspberry Pi Pico W.
//
// EA DOGM162W-A (2 x 16, 3.3 V). Every Full method: text, cursor and blink, entry mode, shift, contrast, booster, follower, bias, custom characters, display on/off.
// Wiring: SI=GP19 (SPI0 TX), SCL=GP18, CSB=GP17, RS=GP20 (C/D), RESET=GP21; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
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
		Frequency: 1_000_000,
		Mode:      0,
	}))
	dc := connection.NewGpioOutputPin(machine.GP20)                                                                               // Create RS (C/D) pin, (pin=GP20) → *GpioOutputPin
	res := connection.NewGpioOutputPin(machine.GP21)                                                                              // Create RESET pin, (pin=GP21) → *GpioOutputPin
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, nil, connection.WireModeSimplex) // Create display connection, (spi, cs, dc, res, busy=nil, wireMode)
	must(err)
	lcd, err := display.NewST7036Full(conn, 2, 0, display.ST7036Supply3V3, -1) // Create ST7036 driver, (conn, lines=2, columns=0 default, supply=3V3, contrast=-1 preset) → (*ST7036Full, error)
	must(err)

	must(lcd.Clear()) // Clear display, () → error
	// writes spaces to all of DDRAM, cursor to home (2 ms)
	must(lcd.PrintAt(0, 0, "Complete example")) // Write text, (col, row, text) → error
	must(lcd.SetCursor(0, 1))                   // Move cursor, (col, row) → error
	must(lcd.Write("Cursor + blink"))           // Write text at the cursor, (text) → error
	must(lcd.Cursor(true, true))                // Cursor, (on, blink) → error
	time.Sleep(2 * time.Second)
	must(lcd.Cursor(false, false)) // Cursor, (on, blink) → error

	heart := [8]uint8{0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00}
	arrow := [8]uint8{0x04, 0x02, 0x1F, 0x02, 0x04, 0x00, 0x00, 0x00}
	must(lcd.CreateChar(0, heart)) // Define user character, (index 0..7, rows[8]) → error
	// 8 rows of 5 bits into CGRAM, DDRAM address restored
	must(lcd.CreateChar(1, arrow)) // Define user character, (index 0..7, rows[8]) → error
	must(lcd.Clear())              // Clear display, () → error
	must(lcd.SetCursor(0, 0))      // Move cursor, (col, row) → error
	must(lcd.Write("I "))          // Write text at the cursor, (text) → error
	must(lcd.WriteChar(0))         // Write raw character code, (code) → error
	must(lcd.Write(" custom "))    // Write text at the cursor, (text) → error
	must(lcd.WriteChar(1))         // Write raw character code, (code) → error
	time.Sleep(2 * time.Second)

	must(lcd.SetContrast(0x08))             // Set contrast, (value 0..63) → error
	must(lcd.PrintAt(0, 1, "low contrast")) // Write text, (col, row, text) → error
	time.Sleep(1500 * time.Millisecond)
	must(lcd.SetContrast(0x30))              // Set contrast, (value 0..63) → error
	must(lcd.PrintAt(0, 1, "high contrast")) // Write text, (col, row, text) → error
	time.Sleep(1500 * time.Millisecond)
	must(lcd.SetContrast(0x18)) // Set contrast, (value 0..63) → error
	// 0x18 is the DOGM162 3.3 V preset
	must(lcd.SetBias(false))       // LCD bias, (quarter) → error, 1/5 bias
	must(lcd.SetFollower(true, 5)) // Voltage follower, (on, ratio 0..7) → error, 1 + Rb/Ra = 2.5
	must(lcd.SetBooster(true))     // Voltage booster, (on) → error

	must(lcd.Clear())                           // Clear display, () → error
	must(lcd.SetEntryMode(true, false))         // Entry mode, (increment, shift) → error
	must(lcd.PrintAt(0, 0, "Shift display ->")) // Write text, (col, row, text) → error
	must(lcd.PrintAt(0, 1, "0123456789ABCDEF")) // Write text, (col, row, text) → error
	time.Sleep(time.Second)
	for i := 0; i < 6; i++ {
		must(lcd.ShiftDisplay(true)) // Shift display, (right) → error
		time.Sleep(300 * time.Millisecond)
	}
	must(lcd.ReturnHome())          // Return home, () → error, undoes the display shift
	must(lcd.ShiftCursor(true))     // Shift cursor, (right) → error
	must(lcd.SetDDRAMAddress(0x40)) // Set DDRAM address, (address) → error
	time.Sleep(time.Second)

	must(lcd.Display(false)) // Display on/off, (on) → error
	time.Sleep(800 * time.Millisecond)
	must(lcd.Display(true)) // Display on/off, (on) → error
	must(conn.Close())
}

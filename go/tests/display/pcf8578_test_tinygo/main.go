//go:build tinygo

// PCF8578 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a PCF8578 on I2C1 (GP4 = SDA, GP5 = SCL).
// Prints PASS/FAIL per check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x3C, nil, nil)

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	// Minimal: construct (clears RAM, display on), pixels, columns.
	lcd, err := display.NewPCF8578Minimal(conn, 16)
	if err != nil {
		fmt.Println("FAIL new:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	check("minimal_draw", lcd.Clear() == nil &&
		lcd.SetPixel(0, 0, true) == nil &&
		lcd.SetPixel(23, 15, true) == nil &&
		lcd.SetPixel(30, 30, true) == nil && // out of range, ignored
		lcd.WriteColumns(2, 1, []byte{0xFF, 0x81, 0xFF}) == nil)

	// Full: exercise every method; ReadRAM verifies the bytes really landed.
	full, err := display.NewPCF8578Full(conn, 16)
	if err != nil {
		fmt.Println("FAIL new full:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	check("write_ram", full.WriteRAM(16, 0, []byte{0xAA, 0x55, 0x0F}, display.PCF8578AccessCharacter) == nil)
	got, err := full.ReadRAM(16, 0, 3, display.PCF8578AccessCharacter)
	check("ram_roundtrip", err == nil && len(got) == 3 && got[0] == 0xAA && got[1] == 0x55 && got[2] == 0x0F)

	check("graphics", full.Fill(true) == nil && full.Fill(false) == nil &&
		full.DrawLine(0, 0, 23, 15, true) == nil &&
		full.DrawRect(0, 0, 24, 16, true, false) == nil &&
		full.DrawRect(4, 4, 8, 8, true, true) == nil &&
		full.ShowBitmap(0, 0, [][]byte{{0x18, 0x24, 0x42, 0x81}}) == nil &&
		full.GetPixel(0, 0))

	check("configuration", full.SetDisplay(display.PCF8578DisplayInverse) == nil &&
		full.SetDisplay(display.PCF8578DisplayAllOn) == nil &&
		full.SetDisplay(display.PCF8578DisplayNormal) == nil &&
		full.SetStartBank(1) == nil && full.Scroll(1) == nil && full.Scroll(-2) == nil &&
		full.DeviceSelect(1) == nil && full.DeviceSelect(0) == nil &&
		full.WriteRAM(16, 0, make([]byte, 8), display.PCF8578AccessHalfGraphic) == nil &&
		full.WriteRAM(16, 0, make([]byte, 8), display.PCF8578AccessFullGraphic) == nil &&
		full.SetMode(24, true) == nil && full.SetMode(16, true) == nil &&
		full.Reset() == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

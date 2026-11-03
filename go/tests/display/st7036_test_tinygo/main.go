//go:build tinygo

// ST7036 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Wiring: SI=GP19 (SPI0 TX), SCL=GP18, CSB=GP17, RS=GP20, RESET=GP21.
// The picture itself is verified by eye (see the demo example). Prints PASS/FAIL per
// check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	if err := machine.SPI0.Configure(machine.SPIConfig{
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Frequency: 1_000_000,
		Mode:      0,
	}); err != nil {
		fmt.Printf("FAIL spi_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	dc := connection.NewGpioOutputPin(machine.GP20)
	res := connection.NewGpioOutputPin(machine.GP21)
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, nil, connection.WireModeSimplex)
	if err != nil {
		fmt.Printf("FAIL connection: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	lcd, err := display.NewST7036Full(conn, 2, 0, display.ST7036Supply3V3, -1)
	check("init_valid", err == nil)
	if err != nil {
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		return
	}
	check("geometry_16x2", lcd.Columns() == 16 && lcd.Rows() == 2)
	ok := lcd.Clear() == nil &&
		lcd.PrintAt(0, 0, "Periph ST7036") == nil &&
		lcd.PrintAt(0, 1, "smoke test") == nil
	check("text_accepted", ok)
	ok = lcd.CreateChar(0, [8]uint8{0x1F, 0x11, 0x15, 0x11, 0x1F, 0x00, 0x1F, 0x00}) == nil &&
		lcd.SetCursor(15, 1) == nil &&
		lcd.WriteChar(0) == nil &&
		lcd.Cursor(true, true) == nil &&
		lcd.Cursor(false, false) == nil &&
		lcd.SetEntryMode(true, false) == nil &&
		lcd.ShiftDisplay(true) == nil &&
		lcd.ShiftDisplay(false) == nil &&
		lcd.ShiftCursor(true) == nil &&
		lcd.ReturnHome() == nil &&
		lcd.SetDDRAMAddress(0x40) == nil &&
		lcd.SetContrast(40) == nil &&
		lcd.SetBooster(true) == nil &&
		lcd.SetFollower(true, 5) == nil &&
		lcd.SetBias(false) == nil &&
		lcd.SetContrast(24) == nil &&
		lcd.Display(false) == nil &&
		lcd.Display(true) == nil &&
		lcd.Home() == nil
	check("commands_accepted", ok)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

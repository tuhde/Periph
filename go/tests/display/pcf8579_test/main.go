//go:build linux && !tinygo

// PCF8579 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the PCF8579 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x3C"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

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
	lcd, err := display.NewPCF8579Minimal(conn, 0, 16, false)
	if err != nil {
		fmt.Println("FAIL new:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}
	check("minimal_draw", lcd.Clear() == nil &&
		lcd.SetPixel(0, 0, true) == nil &&
		lcd.SetPixel(39, 15, true) == nil &&
		lcd.SetPixel(45, 30, true) == nil && // out of range, ignored
		lcd.WriteColumns(2, 1, []byte{0xFF, 0x81, 0xFF}) == nil)

	// Full: exercise every method; ReadRAM verifies the bytes really landed.
	full, err := display.NewPCF8579Full(conn, 0, 16, false)
	if err != nil {
		fmt.Println("FAIL new full:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}
	check("write_ram", full.WriteRAM(16, 0, []byte{0xAA, 0x55, 0x0F}, display.PCF8579AccessCharacter) == nil)
	got, err := full.ReadRAM(16, 0, 3, display.PCF8579AccessCharacter)
	check("ram_roundtrip", err == nil && len(got) == 3 && got[0] == 0xAA && got[1] == 0x55 && got[2] == 0x0F)

	check("graphics", full.Fill(true) == nil && full.Fill(false) == nil &&
		full.DrawLine(0, 0, 39, 15, true) == nil &&
		full.DrawRect(0, 0, 40, 16, true, false) == nil &&
		full.DrawRect(4, 4, 8, 8, true, true) == nil &&
		full.ShowBitmap(0, 0, []byte{0x18, 0x24, 0x42, 0x81}) == nil &&
		full.GetPixel(0, 0))

	check("configuration", full.SetDisplay(display.PCF8579DisplayInverse) == nil &&
		full.SetDisplay(display.PCF8579DisplayAllOn) == nil &&
		full.SetDisplay(display.PCF8579DisplayNormal) == nil &&
		full.SetStartBank(1) == nil && full.Scroll(1) == nil && full.Scroll(-2) == nil &&
		full.WriteRAMCascade(make([]byte, 160), display.PCF8579AccessFullGraphic) == nil &&
		full.WriteRAM(16, 0, make([]byte, 8), display.PCF8579AccessHalfGraphic) == nil &&
		full.WriteRAM(16, 0, make([]byte, 8), display.PCF8579AccessFullGraphic) == nil &&
		full.SetMode(24, false) == nil && full.SetMode(16, false) == nil &&
		full.Reset() == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

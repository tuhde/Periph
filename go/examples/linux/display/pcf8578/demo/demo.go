//go:build linux && !tinygo

// PCF8578 demo — Linux host.
//
// Border, diagonals, inverse flashes and hardware scrolling on a 16 x 24 panel.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x3C"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x3C) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	// --- Initialise a 16 x 24 dot-matrix panel ---
	// 1:16 multiplex gives 16 rows and 24 columns; the constructor clears the RAM
	// before switching the display on so no random start-up pattern is shown.
	lcd, err := display.NewPCF8578Full(conn, 16) // Create PCF8578 driver, (connection, rows=16 8/16/24/32) → (*PCF8578Full, error)
	if err != nil {
		panic(err)
	}
	must := func(err error) {
		if err != nil {
			panic(err)
		}
	}

	// --- Draw a border and both diagonals ---
	// Lines and rectangles are rendered in the shadow buffer and sent as one
	// batch of changed bytes per run of columns.
	must(lcd.DrawRect(0, 0, 24, 16, true, false)) // Draw rectangle outline, (x, y, w, h, on, fill) → error
	must(lcd.DrawLine(0, 0, 23, 15, true))        // Draw line, (x0, y0, x1, y1, on) → error
	must(lcd.DrawLine(0, 15, 23, 0, true))        // Draw line, (x0, y0, x1, y1, on) → error

	// --- Flash the display twice with inverse video ---
	// Inverse video only changes how the RAM is shown, so the picture is kept.
	for i := 0; i < 2; i++ {
		must(lcd.SetDisplay(display.PCF8578DisplayInverse)) // Set display status, (status 0–3) → error
		time.Sleep(400 * time.Millisecond)
		must(lcd.SetDisplay(display.PCF8578DisplayNormal)) // Set display status, (status 0–3) → error
		time.Sleep(400 * time.Millisecond)
	}

	// --- Fill all four RAM banks with distinct patterns ---
	// At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
	// buffers that the start bank can scroll into view.
	patterns := []byte{0x55, 0x33, 0x0F, 0xFF}
	for bank, p := range patterns {
		col := make([]byte, 24)
		for i := range col {
			col[i] = p
		}
		must(lcd.WriteRAM(16, bank, col, display.PCF8578AccessCharacter)) // Write raw RAM, (x 0–39, bank 0–3, data, access) → error
	}

	// --- Scroll through the banks with the hardware start bank ---
	// Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
	for bank := 0; bank < 4; bank++ {
		must(lcd.SetStartBank(uint8(bank)))                                    // Set start bank, (bank 0–3) → error
		first, err := lcd.ReadRAM(16, bank, 1, display.PCF8578AccessCharacter) // Read raw RAM, (x 0–39, bank 0–3, n, access) → ([]byte, error)
		must(err)
		fmt.Printf("start bank %d first column byte 0x%02X\n", bank, first[0])
		time.Sleep(time.Second)
	}

	// --- Read back the first 8 RAM columns of bank 0 ---
	// RAM columns 0-7 are never displayed at 1:16, so they serve as scratch
	// storage; the chip supports reads in mixed mode, so the bytes can be verified.
	must(lcd.SetStartBank(0))                                                                // Set start bank, (bank 0–3) → error
	must(lcd.WriteRAM(0, 0, []byte{1, 2, 3, 4, 5, 6, 7, 8}, display.PCF8578AccessCharacter)) // Write raw RAM, (x 0–39, bank 0–3, data, access) → error
	back, err := lcd.ReadRAM(0, 0, 8, display.PCF8578AccessCharacter)                        // Read raw RAM, (x 0–39, bank 0–3, n, access) → ([]byte, error)
	must(err)
	fmt.Println(back)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

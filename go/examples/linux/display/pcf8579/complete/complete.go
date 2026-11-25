//go:build linux && !tinygo

// PCF8579 complete example — Linux host.
//
// Exercises every method of the Full driver.
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

	lcd, err := display.NewPCF8579Full(conn, 0, 16, false) // Create PCF8579 driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false) → (*PCF8579Full, error)
	// clears RAM and switches the display on in mixed mode
	if err != nil {
		panic(err)
	}
	must := func(err error) {
		if err != nil {
			panic(err)
		}
	}

	must(lcd.Clear()) // Clear display RAM, () → error
	// zeroes all 4 banks x 40 columns and the shadow
	must(lcd.SetPixel(1, 1, true)) // Set one pixel, (x 0–39, y 0–15, on) → error
	// writes a single RAM byte from the shadow
	must(lcd.WriteColumns(4, 0, []byte{0xFF, 0x81, 0xFF})) // Write column bytes, (x 0–39, bank 0–1, data) → error
	// character access mode, bit 0 = top row of the bank
	on := lcd.GetPixel(1, 1) // Read pixel from shadow, (x, y) → bool
	// no bus traffic
	must(lcd.Fill(true)) // Fill display, (on) → error
	// every displayed pixel on
	must(lcd.Fill(false)) // Fill display, (on) → error
	// every displayed pixel off
	must(lcd.DrawLine(0, 0, 39, 15, true)) // Draw line, (x0, y0, x1, y1, on) → error
	// Bresenham, written as one batch of changed bytes
	must(lcd.DrawRect(0, 0, 40, 16, true, false)) // Draw rectangle outline, (x, y, w, h, on, fill) → error
	// border around the whole panel
	must(lcd.DrawRect(8, 4, 8, 8, true, true)) // Draw filled rectangle, (x, y, w, h, on, fill) → error
	// solid 8x8 block in the middle
	must(lcd.ShowBitmap(2, 8, []byte{0x18, 0x24, 0x42, 0x81})) // Blit bitmap, (x, y multiple of 8, columns of bytes) → error
	// one 8-pixel-high band at the lower bank
	must(lcd.SetDisplay(display.PCF8579DisplayInverse)) // Set display status, (status 0 blank/1 normal/2 all on/3 inverse) → error
	// inverse video; RAM is preserved
	must(lcd.SetDisplay(display.PCF8579DisplayNormal)) // Set display status, (status 0–3) → error
	// back to normal video
	must(lcd.SetMode(24, false)) // Set multiplex rate, (rows 8/16/24/32, mixed) → error
	// 1:24 drive; mixed is the PCF8578 mode bit T
	must(lcd.SetMode(16, false)) // Set multiplex rate, (rows 8/16/24/32, mixed) → error
	// back to 1:16
	must(lcd.SetStartBank(1)) // Set start bank, (bank 0–3) → error
	// shows RAM bank 1 at the top (hardware scroll)
	must(lcd.Scroll(-1)) // Scroll by banks, (banks int) → error
	// adds -1 to the start bank modulo banks in use
	must(lcd.WriteRAM(16, 0, []byte{0xAA, 0x55}, display.PCF8579AccessCharacter)) // Write raw RAM, (x 0–39, bank 0–3, data, access) → error
	// character order at RAM column 16
	data, err := lcd.ReadRAM(16, 0, 2, display.PCF8579AccessCharacter) // Read raw RAM, (x 0–39, bank 0–3, n, access) → ([]byte, error)
	// address-set commands, repeated start, then a read
	must(err)
	must(lcd.WriteRAMCascade(make([]byte, 160), display.PCF8579AccessFullGraphic)) // Write across devices, (data, access) → error
	// runs on into the following subaddresses after column 39
	must(lcd.Reset()) // Re-initialise, () → error
	// clears RAM and restores normal video
	fmt.Println(on, data)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

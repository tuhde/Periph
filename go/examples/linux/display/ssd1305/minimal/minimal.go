//go:build linux && !tinygo

// SSD1305 minimal example — Linux host.
//
// 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) on /dev/i2c-N. Wiring: SDA=GPIO2, SCL=GPIO3 (Raspberry Pi), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump);
// address 0x3C (SA0 = low) or 0x3D (SA0 = high). Minimal use: a border and a diagonal on the 128 x 64 panel.
package main

import (
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

	lcd, err := display.NewSSD1305Minimal(conn, 0, 0, 4) // Create SSD1305 driver, (conn, width=128, height=64, columnOffset=4) → (*SSD1305Minimal, error)
	if err != nil {
		panic(err)
	}

	lcd.Clear() // Clear framebuffer, ()
	for x := 0; x < 128; x++ {
		lcd.SetPixel(x, 0, true)   // Set pixel, (x 0..127, y 0..63, on)
		lcd.SetPixel(x, 63, true)  // Set pixel, (x 0..127, y 0..63, on)
		lcd.SetPixel(x, x/2, true) // Set pixel, (x 0..127, y 0..63, on)
	}
	for y := 0; y < 64; y++ {
		lcd.SetPixel(0, y, true)   // Set pixel, (x 0..127, y 0..63, on)
		lcd.SetPixel(127, y, true) // Set pixel, (x 0..127, y 0..63, on)
	}
	if err := lcd.Show(); err != nil { // Send framebuffer to the display, () → error
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

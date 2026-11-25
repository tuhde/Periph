//go:build linux && !tinygo

// PCF8579 minimal example — Linux host.
//
// Clears the display and lights a few pixels.
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

	lcd, err := display.NewPCF8579Minimal(conn, 0, 16, false) // Create PCF8579 driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false) → (*PCF8579Minimal, error)
	if err != nil {
		panic(err)
	}

	steps := []struct {
		name string
		fn   func() error
	}{
		{"clear", lcd.Clear}, // Clear display RAM, () → error
		{"pixel 0,0", func() error { return lcd.SetPixel(0, 0, true) }},                             // Set one pixel, (x 0–39, y 0–15, on) → error
		{"pixel 39,15", func() error { return lcd.SetPixel(39, 15, true) }},                         // Set opposite corner, (x 0–39, y 0–15, on) → error
		{"columns", func() error { return lcd.WriteColumns(4, 0, []byte{0xFF, 0x81, 0x81, 0xFF}) }}, // Write column bytes, (x 0–39, bank 0–1, data) → error
	}
	for _, s := range steps {
		if err := s.fn(); err != nil {
			panic(err)
		}
		fmt.Println("done:", s.name)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

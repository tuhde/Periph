//go:build tinygo

// PCF8578 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL
// (100 kHz, the chip's maximum). Clears the display and lights a few pixels.
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
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x3C, nil, nil) // Create I2C connection, (i2c, addr=0x3C) → (*I2CConnection)
	lcd, err := display.NewPCF8578Minimal(conn, 16)          // Create PCF8578 driver, (connection, rows=16 8/16/24/32) → (*PCF8578Minimal, error)
	if err != nil {
		panic(err)
	}

	steps := []struct {
		name string
		fn   func() error
	}{
		{"clear", lcd.Clear}, // Clear display RAM, () → error
		{"pixel 0,0", func() error { return lcd.SetPixel(0, 0, true) }},                             // Set one pixel, (x 0–23, y 0–15, on) → error
		{"pixel 23,15", func() error { return lcd.SetPixel(23, 15, true) }},                         // Set opposite corner, (x 0–23, y 0–15, on) → error
		{"columns", func() error { return lcd.WriteColumns(4, 0, []byte{0xFF, 0x81, 0x81, 0xFF}) }}, // Write column bytes, (x 0–23, bank 0–1, data) → error
	}
	for _, s := range steps {
		if err := s.fn(); err != nil {
			panic(err)
		}
		fmt.Println("done:", s.name)
	}
}

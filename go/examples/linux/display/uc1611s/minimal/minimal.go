//go:build linux && !tinygo

// UC1611s minimal example — Linux host.
//
// EA DOGXL240-7 (240 x 128, 3.3 V only) on /dev/i2c-N. Wiring: SDA=GPIO2, SCL=GPIO3 (Raspberry Pi), VDD = 3V3;
// module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39. Capacitors: 4 x 4.7 uF/5 V (VA0, VA1,
// VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Minimal use: a border and a diagonal on the 240 x 128 panel.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	if err != nil {
		panic(err)
	}

	command, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create command connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer command.Close()
	data, err := connection.NewI2CConnection(bus, uint8(addr)+1, nil, nil) // Create data connection, (bus=1, addr=0x39) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer data.Close()

	lcd, err := display.NewUC1611sMinimal(command, data, 0, 0) // Create UC1611s driver, (command, data, width=240, height=128) → (*UC1611sMinimal, error)
	if err != nil {
		panic(err)
	}

	lcd.Clear() // Clear framebuffer, ()
	for x := 0; x < 240; x++ {
		lcd.SetPixel(x, 0, true)         // Set pixel, (x 0..239, y 0..127, on)
		lcd.SetPixel(x, 127, true)       // Set pixel, (x 0..239, y 0..127, on)
		lcd.SetPixel(x, x*127/239, true) // Set pixel, (x 0..239, y 0..127, on)
	}
	for y := 0; y < 128; y++ {
		lcd.SetPixel(0, y, true)   // Set pixel, (x 0..239, y 0..127, on)
		lcd.SetPixel(239, y, true) // Set pixel, (x 0..239, y 0..127, on)
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

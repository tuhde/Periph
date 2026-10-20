//go:build tinygo

// UC1611s minimal example — TinyGo / Raspberry Pi Pico W.
//
// EA DOGXL240-7 (240 x 128, 3.3 V only) on machine.I2C1 with GP4 = SDA and GP5 = SCL, VDD = 3V3; module pins
// BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39. Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1
// pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Minimal use: a border and a diagonal on the 240 x 128 panel.
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	command := connection.NewI2CConnection(i2c, 0x38, nil, nil) // Create command connection, (i2c, addr=0x38) → (*I2CConnection)
	data := connection.NewI2CConnection(i2c, 0x39, nil, nil)    // Create data connection, (i2c, addr=0x39) → (*I2CConnection)
	lcd, err := display.NewUC1611sMinimal(command, data, 0, 0)  // Create UC1611s driver, (command, data, width=240, height=128) → (*UC1611sMinimal, error)
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

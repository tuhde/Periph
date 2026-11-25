//go:build tinygo

// SSD1306 minimal example — TinyGo / Raspberry Pi Pico W.
//
// 0.96" 128 x 64 OLED module (SSD1306) on machine.I2C1 with GP4 = SDA and GP5 = SCL, VCC = 3V3 (the module generates the OLED supply with the SSD1306 charge pump);
// address 0x3C (SA0 = low) or 0x3D (SA0 = high). Minimal use: a border and a diagonal on the 128 x 64 panel.
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

	conn := connection.NewI2CConnection(i2c, 0x3C, nil, nil) // Create I2C connection, (i2c, addr=0x3C) → (*I2CConnection)
	lcd, err := display.NewSSD1306Minimal(conn, 0, 0, false) // Create SSD1306 driver, (conn, width=128, height=64, externalVCC=false) → (*SSD1306Minimal, error)
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

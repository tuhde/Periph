//go:build tinygo

// PCF8576 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and writes four 7-segment digits to the LCD panel.
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
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil)                // Create I2C connection, (i2c, addr=0x38) → (*I2CConnection)
	lcd, err := display.NewPCF8576Minimal(conn)                  // Create PCF8576 driver, (connection) → (*PCF8576Minimal, error)
	if err != nil {
		panic(err)
	}

	digits := [4]uint8{1, 2, 3, 4}
	for i, d := range digits {
		seg := display.PCF8576SevenSeg[d] // Encode 7-segment digit, (digit 0–9) → uint8
		if err := lcd.SetDigit7seg(uint8(i), seg); err != nil { // Write one digit, (position 0–19, segments 0–255) → error
			panic(err)
		}
	}
	fmt.Println("wrote 1234")
}

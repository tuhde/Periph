//go:build tinygo

// AS7262 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Reads the six calibrated channels and the temperature every two seconds.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/color"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x49, nil, nil) // Create I2C connection, (i2c, addr=0x49, ) → (*I2CConnection)

	chip, err := color.NewAS7262Minimal(conn) // Create AS7262 driver, (connection) → (*AS7262Minimal, error)
	chk(err)

	for {
		c, err := chip.Read() // Read six calibrated channels, () → (AS7262Channels[float32], error)
		chk(err)
		fmt.Printf("V %.1f B %.1f G %.1f Y %.1f O %.1f R %.1f\n", c.Violet, c.Blue, c.Green, c.Yellow, c.Orange, c.Red)
		t, err := chip.Temperature() // Read device temperature, () → (float32 °C, error)
		chk(err)
		fmt.Printf("%.0f C\n", t)
		time.Sleep(2 * time.Second)
	}
}

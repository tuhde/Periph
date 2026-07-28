//go:build tinygo

// APDS-9960 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver with a configured I2C1 peripheral and prints
// RGBC channel values once per second.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/chips/light"
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

	conn := connection.NewI2CConnection(i2c, 0x39, nil, nil)              // Create I2C connection, (i2c, addr=0x39) → *I2CConnection
	chip, err := light.NewAPDS9960Minimal(conn)               // Create APDS-9960 driver, (connection) → (*APDS9960Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		c, r, g, b, err := chip.Color() // Read all four RGBC channels, () → (clear, red, green, blue uint16, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("C=%d R=%d G=%d B=%d\n", c, r, g, b)
		time.Sleep(time.Second)
	}
}

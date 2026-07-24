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

	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x39)              // Create I2C transport, (i2c, addr=0x39) → *I2CTransport
	chip, err := light.NewAPDS9960Minimal(tr)               // Create APDS-9960 driver, (transport) → (*APDS9960Minimal, error)
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

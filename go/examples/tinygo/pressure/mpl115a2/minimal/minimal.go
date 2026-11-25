//go:build tinygo

// MPL115A2 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then reads pressure and temperature in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil) // Create I2C connection, (i2c, addr=0x60) → (*I2CConnection)
	chip, err := pressure.NewMpl115a2Minimal(conn)           // Create MPL115A2 driver, (connection) → (*Mpl115a2Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		p, err := chip.ReadPressure() // Read pressure, () → (float64 Pa, error)
		if err != nil {
			panic(err)
		}
		t, err := chip.ReadTemperature() // Read temperature, () → (float64 C approximate, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("P=%.0f Pa, T=%.1f C\n", p, t)
		time.Sleep(time.Second)
	}
}

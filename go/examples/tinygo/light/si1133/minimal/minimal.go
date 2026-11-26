//go:build tinygo

// Si1133 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then prints the UV and white counts once per second.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
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

	conn := connection.NewI2CConnection(i2c, 0x55, nil, nil) // Create I2C connection, (i2c, addr=0x55) → (*I2CConnection)

	chip, err := light.NewSi1133Minimal(conn) // Create Si1133 driver, (connection) → (*Si1133Minimal, error)
	chk(err)
	chk(chip.Init()) // Check PART_ID, reset, UV + white channels, () → error

	for {
		m, err := chip.Read() // Forced measurement, () → (Si1133Reading {UV counts, White counts}, error)
		chk(err)
		fmt.Printf("UV %d, white %d\n", m.UV, m.White)
		time.Sleep(time.Second)
	}
}

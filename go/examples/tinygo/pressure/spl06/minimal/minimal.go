//go:build tinygo

// SPL06 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then reads temperature and pressure every second.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	conn := connection.NewI2CConnection(i2c, 0x77, nil, nil) // Create I2C connection, (i2c, addr=0x77) → (*I2CConnection)

	chip, err := pressure.NewSPL06Minimal(conn) // Create SPL06 driver, (connection) → (*SPL06Minimal, error)
	chk(err)
	chk(chip.Init()) // Check ID, read coefficients, apply defaults, () → error

	for {
		m, err := chip.Read() // Measure temperature and pressure, () → (SPL06Measurement {TemperatureC °C, PressurePa Pa}, error)
		chk(err)
		fmt.Printf("%.2f Pa, %.2f C\n", m.PressurePa, m.TemperatureC)
		time.Sleep(time.Second)
	}
}

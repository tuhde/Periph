//go:build tinygo

// BMP085 minimal example — TinyGo (Pico W).
//
// Constructs the driver using machine.I2C0, then reads temperature
// and pressure in a loop.
package main

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
	"machine"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{
		Frequency: 100_000,
		SDA:       machine.GPIO4,
		SCL:       machine.GPIO5,
	})
	conn, err := connection.NewI2CConnection(machine.I2C0, 0x77, nil, nil) // Create I2C connection, (bus=I2C0, addr=0x77) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewBmp085Minimal(conn) // Create BMP085 driver, (connection) → (*Bmp085Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, err := chip.Temperature() // Read temperature, () → (float64 C, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Pressure() // Read pressure, () → (float64 Pa, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("T=%.1f C, P=%.1f Pa\n", t, p)
		time.Sleep(time.Second)
	}
}
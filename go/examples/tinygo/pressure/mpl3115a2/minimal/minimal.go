//go:build tinygo

// MPL3115A2 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then reads pressure and temperature every two seconds.
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

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil) // Create I2C connection, (i2c, addr=0x60, ) → (*I2CConnection)

	chip := pressure.NewMPL3115A2Minimal(conn) // Create MPL3115A2 driver, (connection) → *MPL3115A2Minimal
	chk(chip.Init())                           // Reset and apply defaults, () → error

	for {
		p, t, err := chip.Read() // Read pressure and temperature, () → (float32 Pa, float32 °C, error)
		chk(err)
		fmt.Printf("%.2f Pa, %.2f C\n", p, t)
		time.Sleep(2 * time.Second)
	}
}

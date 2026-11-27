//go:build tinygo

// Si1145 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then prints proximity, UV index and light once per second.
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

var _ = fmt.Sprint
var _ = time.Second

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil) // Create I2C connection, (i2c, addr=0x60) → (*I2CConnection)

	chip, err := light.NewSi1145Minimal(conn) // Create Si1145 driver, (connection) → (*Si1145Minimal, error)
	chk(err)
	chk(chip.Init()) // Check PART_ID, HW_KEY, UV + ALS + PS1, () → error

	for {
		r, err := chip.Read() // Forced PS + ALS, () → (Si1145Reading {Proximity, UVIndex, Visible, Infrared}, error)
		chk(err)
		fmt.Printf("prox %d  UV %.2f  vis %d  ir %d\n", r.Proximity, r.UVIndex, r.Visible, r.Infrared)
		time.Sleep(time.Second)
	}
}

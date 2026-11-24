//go:build tinygo

// Si1132 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Reads the UV index and the visible and infrared counts every second.
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

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil) // Create I2C connection, (i2c, addr=0x60, ) → (*I2CConnection)

	chip := light.NewSi1132Minimal(conn) // Create Si1132 driver, (connection) → *Si1132Minimal
	chk(chip.Init())                     // Verify PART_ID and start forced mode, () → error

	for {
		r, v, ir, err := chip.Read() // Read UV index and ALS counts, () → (float32 UV index, uint16 visible counts, uint16 infrared counts, error)
		chk(err)
		fmt.Printf("UV %.2f, visible %d, infrared %d\n", r, v, ir)
		time.Sleep(time.Second)
	}
}

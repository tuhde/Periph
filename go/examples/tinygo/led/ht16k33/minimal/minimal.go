//go:build tinygo

// HT16K33 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	must(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x70, nil, nil) // Create I2C connection, (i2c, addr=0x70) → (*I2CConnection)

	matrix, err := led.NewHT16K33Minimal(conn) // Create HT16K33 driver, (connection) → (*HT16K33Minimal, error)
	if err != nil {
		panic(err)
	}

	for row := uint8(0); row < led.HT16K33Rows; row++ {
		if err := matrix.SetPixel(0, row, true); err != nil { // Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → error
			panic(err)
		}
	}
	if err := matrix.Show(); err != nil { // Write the shadow RAM to the chip, () → error
		panic(err)
	}
	time.Sleep(time.Second)
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

//go:build tinygo

// BH1750 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then measures the illuminance every second.
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

	conn := connection.NewI2CConnection(i2c, 0x23, nil, nil) // Create I2C connection, (i2c, addr=0x23, ) → (*I2CConnection)

	chip := light.NewBH1750Minimal(conn) // Create BH1750 driver, (connection) → *BH1750Minimal
	chk(chip.Init())                     // Power on, () → error

	for {
		lux, err := chip.Read() // Measure illuminance, () → (float32 lx, error)
		chk(err)
		fmt.Printf("%.1f lx\n", lux)
		time.Sleep(time.Second)
	}
}

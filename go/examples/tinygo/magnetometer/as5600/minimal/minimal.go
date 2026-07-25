//go:build tinygo

// AS5600 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints the absolute angle in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x36) // Create I2C transport, (i2c, addr=0x36) → (*I2CTransport)
	chip, err := magnetometer.NewAs5600Minimal(tr) // Create AS5600 driver, (transport) → (*As5600Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		a, err := chip.Angle() // Read absolute angle, () → (float64 degrees, error)
		if err != nil {
			println("angle:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		r, err := chip.AngleRaw() // Read scaled angle count, () → (uint16 0-4095, error)
		if err != nil {
			println("angle_raw:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("angle=%.2f deg  raw=%d\n", a, r)
		time.Sleep(time.Second)
	}
}

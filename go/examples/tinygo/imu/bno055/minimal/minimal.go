//go:build tinygo

// BNO055 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then loops reading the fused Euler angles ten times per second.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

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
		Frequency: 100_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x29, nil, nil) // Create I2C connection, (i2c, addr=0x29) → (*I2CConnection)

	chip, err := imu.NewBNO055Minimal(conn) // Create BNO055 driver, (connection) → (*BNO055Minimal, error)
	must(err)

	for {
		h, r, p, err := chip.Euler() // Read fused Euler angles, () → (float32, float32, float32 rad, error)
		must(err)
		fmt.Printf("heading %.2f rad, roll %.2f, pitch %.2f\n", h, r, p)
		time.Sleep(100 * time.Millisecond)
	}
}

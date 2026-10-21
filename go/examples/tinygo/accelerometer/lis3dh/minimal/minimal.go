//go:build tinygo

// LIS3DH minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, 0x18, nil, nil) // Create I2C connection, (i2c, addr=0x18) → *I2CConnection
	defer conn.Close()

	chip, err := accelerometer.NewLIS3DHMinimal(conn, 0x18) // Create LIS3DH driver, (connection, addr=0x18) → (*LIS3DHMinimal, error)
	must(err)
	for {
		x, y, z, err := chip.Read() // Read acceleration, () → (float64 g, float64 g, float64 g, error)
		must(err)
		fmt.Printf("x=%.4f g y=%.4f g z=%.4f g\n", x, y, z)
		time.Sleep(100 * time.Millisecond)
	}
}

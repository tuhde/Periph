//go:build tinygo

// FXOS8700 minimal example — TinyGo (Raspberry Pi Pico W).
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
	conn := connection.NewI2CConnection(machine.I2C0, 0x1E, nil, nil) // Create I2C connection, (i2c, addr=0x1E) → *I2CConnection
	defer conn.Close()

	chip, err := accelerometer.NewFXOS8700Minimal(conn) // Create FXOS8700 driver, (connection) → (*FXOS8700Minimal, error)
	must(err)
	for {
		ax, ay, az, err := chip.ReadAccel() // Read acceleration, () → (float32 g, float32 g, float32 g, error)
		must(err)
		mx, my, mz, err := chip.ReadMag() // Read magnetic field, () → (float32 µT, float32 µT, float32 µT, error)
		must(err)
		fmt.Printf("accel x=%.4f y=%.4f z=%.4f g\n", ax, ay, az)
		fmt.Printf("mag   x=%.2f y=%.2f z=%.2f uT\n", mx, my, mz)
		time.Sleep(time.Second)
	}
}

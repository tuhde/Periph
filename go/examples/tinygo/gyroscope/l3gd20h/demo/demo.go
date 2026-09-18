//go:build tinygo

// L3GD20H demo — TinyGo (Raspberry Pi Pico W).
package main

import (
	"fmt"
	"math"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{
		Frequency: 100 * machine.KHz,
		SDA:       machine.I2C0_SDA_PIN,
		SCL:       machine.I2C0_SCL_PIN,
	})

	conn := connection.NewI2CConnection(machine.I2C0, 0x6A, nil, nil)
	defer conn.Close()

	gyro, err := gyroscope.NewL3GD20HFull(conn, false)
	if err != nil {
		panic(err)
	}

	// --- Configure for shake detection at 190 Hz, ±500 dps ---
	gyro.Configure(gyroscope.L3GD20HODR190Hz, 0, gyroscope.L3GD20HFS500DPS)

	fmt.Println("L3GD20H shake detector running. Shake the device...")

	for {
		drdy, _ := gyro.DataReady()
		if drdy {
			x, y, z, _ := gyro.AngularRate()
			magnitude := math.Sqrt(float64(x*x + y*y + z*z))
			if magnitude > 1.0 {
				fmt.Printf("SHAKE DETECTED: mag=%.3f (x=%.3f y=%.3f z=%.3f)\n", magnitude, x, y, z)
			} else {
				fmt.Printf("x=%.3f y=%.3f z=%.3f mag=%.3f\n", x, y, z, magnitude)
			}
		}
	}
}
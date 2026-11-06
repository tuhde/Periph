//go:build tinygo

// ICM20948 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then loops reading 3-axis acceleration and angular
// rate ten times per second.
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
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x68, nil, nil) // Create I2C connection, (i2c, addr=0x68) → (*I2CConnection)

	chip, err := imu.NewICM20948Minimal(conn) // Create ICM20948 driver, (connection) → (*ICM20948Minimal, error)
	must(err)

	for {
		ax, ay, az, err := chip.Accel() // Read 3-axis acceleration, () → (float32, float32, float32 m/s², error)
		must(err)
		gx, gy, gz, err := chip.Gyro() // Read 3-axis angular rate, () → (float32, float32, float32 rad/s, error)
		must(err)
		fmt.Printf("accel=(%.2f %.2f %.2f) m/s2  gyro=(%.3f %.3f %.3f) rad/s\n", ax, ay, az, gx, gy, gz)
		time.Sleep(100 * time.Millisecond)
	}
}

//go:build tinygo

// MPU9250 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints 3-axis acceleration and angular
// rate in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn := connection.NewI2CConnection(i2c, 0x68, nil, nil)        // Create I2C connection, (i2c, addr=0x68) → (*I2CConnection)
	chip, err := imu.NewMPU9250Minimal(conn)            // Create MPU9250 driver, (connection) → (*MPU9250Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		ax, ay, az, err := chip.Accel() // Read 3-axis acceleration, () → (float32 m/s², float32 m/s², float32 m/s², error)
		if err != nil {
			println("accel:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		gx, gy, gz, err := chip.Gyro() // Read 3-axis angular rate, () → (float32 rad/s, float32 rad/s, float32 rad/s, error)
		if err != nil {
			println("gyro:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("a=(%6.2f %6.2f %6.2f) m/s2  g=(%7.3f %7.3f %7.3f) rad/s\n", ax, ay, az, gx, gy, gz)
		time.Sleep(time.Second)
	}
}

//go:build tinygo

// LSM303 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C0 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver with one connection per slave (accelerometer
// 0x19, magnetometer 0x1E), and prints acceleration and magnetic field
// in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C0
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	accelConn := connection.NewI2CConnection(i2c, 0x19, nil, nil) // Create accelerometer I²C connection, (i2c, addr=0x19) → (*I2CConnection)
	magConn := connection.NewI2CConnection(i2c, 0x1E, nil, nil)   // Create magnetometer I²C connection, (i2c, addr=0x1E) → (*I2CConnection)

	chip, err := accelerometer.NewLSM303Minimal(accelConn, magConn) // Create LSM303 driver, (accelConn, magConn) → (*LSM303Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		x, y, z, err := chip.ReadAcceleration() // Read 3-axis acceleration, () → (float32 g, float32 g, float32 g, error)
		if err != nil {
			println("accel:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		mx, my, mz, err := chip.ReadMagnetic() // Read 3-axis magnetic flux density, () → (float32 µT, float32 µT, float32 µT, error)
		if err != nil {
			println("mag:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("a=(%6.3f %6.3f %6.3f) g  m=(%7.1f %7.1f %7.1f) uT\n", x, y, z, mx, my, mz)
		time.Sleep(time.Second)
	}
}

//go:build tinygo

// LSM303 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	accelConn := connection.NewI2CConnection(machine.I2C0, 0x19, nil, nil) // Create accelerometer connection, (i2c=I2C0, addr=0x19) → *I2CConnection
	magConn := connection.NewI2CConnection(machine.I2C0, 0x1E, nil, nil)   // Create magnetometer connection, (i2c=I2C0, addr=0x1E) → *I2CConnection

	sensor, err := accelerometer.NewLSM303Minimal(accelConn, magConn) // Create LSM303 driver, (accel_conn, mag_conn) → (*LSM303Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		ax, ay, az, err := sensor.ReadAcceleration() // Read acceleration, () → (float32, float32, float32, error) g
		if err != nil {
			panic(err)
		}
		mx, my, mz, err := sensor.ReadMagnetic() // Read magnetic field, () → (float32, float32, float32, error) µT
		if err != nil {
			panic(err)
		}
		println("a:", ax, ay, az, "g  m:", mx, my, mz, "uT")
		time.Sleep(200 * time.Millisecond)
	}
}

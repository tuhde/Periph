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
	accelConn, err := connection.NewI2CConnection(0, 0x19, nil, nil) // Create accelerometer connection, (bus=0, addr=0x19) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(0, 0x1E, nil, nil) // Create magnetometer connection, (bus=0, addr=0x1E) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer magConn.Close()

	sensor, err := accelerometer.NewLSM303Minimal(accelConn, magConn) // Create LSM303 driver, (accelConn, magConn) → (*LSM303Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		ax, ay, az, err := sensor.ReadAcceleration() // Read 3-axis acceleration, () → (float64, float64, float64, error) g
		if err != nil {
			panic(err)
		}
		mx, my, mz, err := sensor.ReadMagnetic() // Read 3-axis magnetic field, () → (float64, float64, float64, error) µT
		if err != nil {
			panic(err)
		}
		println("a=", ax, ay, az, " m=", mx, my, mz)
		time.Sleep(100 * time.Millisecond)
	}
}

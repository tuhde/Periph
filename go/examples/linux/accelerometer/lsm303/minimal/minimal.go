//go:build linux && !tinygo

// LSM303 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	accelConn, err := connection.NewI2CConnection(bus, 0x19, nil, nil) // Create accelerometer connection, (bus=1, addr=0x19) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, 0x1E, nil, nil) // Create magnetometer connection, (bus=1, addr=0x1E) → (*I2CConnection, error)
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
		fmt.Printf("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) µT\n", ax, ay, az, mx, my, mz)
		time.Sleep(100 * time.Millisecond)
	}
}

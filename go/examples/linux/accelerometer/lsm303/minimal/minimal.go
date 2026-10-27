//go:build linux && !tinygo

// LSM303 minimal example — Linux host, read acceleration and magnetic
// field in a loop.
//
// The LSM303DLHC exposes two fixed I²C slaves on one bus: the
// accelerometer at 0x19 and the magnetometer at 0x1E. Both connections
// are opened over the same bus and handed to the driver together.
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

	accelConn, err := connection.NewI2CConnection(bus, 0x19, nil, nil) // Create accelerometer I²C connection, (bus=1, addr=0x19) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, 0x1E, nil, nil) // Create magnetometer I²C connection, (bus=1, addr=0x1E) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer magConn.Close()

	chip, err := accelerometer.NewLSM303Minimal(accelConn, magConn) // Create LSM303 driver, (accelConn, magConn) → (*LSM303Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		x, y, z, err := chip.ReadAcceleration() // Read 3-axis acceleration, () → (float32 g, float32 g, float32 g, error)
		if err != nil {
			panic(err)
		}
		mx, my, mz, err := chip.ReadMagnetic() // Read 3-axis magnetic flux density, () → (float32 µT, float32 µT, float32 µT, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("a=(%6.3f %6.3f %6.3f) g  m=(%7.1f %7.1f %7.1f) uT\n", x, y, z, mx, my, mz)
		time.Sleep(time.Second)
	}
}

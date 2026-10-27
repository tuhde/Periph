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

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	accelConn, err := connection.NewI2CConnection(bus, 0x19, nil, nil) // Create accelerometer connection, (bus, addr=0x19) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, 0x1E, nil, nil) // Create magnetometer connection, (bus, addr=0x1E) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer magConn.Close()

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
		fmt.Printf("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) uT\n", ax, ay, az, mx, my, mz)
		time.Sleep(200 * time.Millisecond)
	}
	os.Exit(0)
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

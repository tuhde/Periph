//go:build linux && !tinygo

// LSM303 minimal example — Linux host, read acceleration and magnetic field in a loop.
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
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	accelConn, err := connection.NewI2CConnection(bus, accelerometer.LSM303I2CAddressAccel, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "accelerometer connection:", err)
		os.Exit(2)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, accelerometer.LSM303I2CAddressMag, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "magnetometer connection:", err)
		os.Exit(2)
	}
	defer magConn.Close()
	chip, err := accelerometer.NewLSM303Minimal(accelConn, magConn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	for {
		ax, ay, az, err := chip.ReadAcceleration()
		if err != nil {
			fmt.Fprintln(os.Stderr, "read acceleration:", err)
			os.Exit(1)
		}
		mx, my, mz, err := chip.ReadMagnetic()
		if err != nil {
			fmt.Fprintln(os.Stderr, "read magnetic:", err)
			os.Exit(1)
		}
		fmt.Printf("accel x=%.4f y=%.4f z=%.4f g\n", ax, ay, az)
		fmt.Printf("mag   x=%.2f y=%.2f z=%.2f uT\n", mx, my, mz)
		time.Sleep(time.Second)
	}
}

//go:build linux && !tinygo

// HMC5883L minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N and prints magnetic field on three axes
// in a loop.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1E"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x1E) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := magnetometer.NewHMC5883LMinimal(conn) // Create HMC5883L driver, (connection) → (*HMC5883LMinimal, error)
	if err != nil {
		panic(err)
	}

	for {
		x, y, z, err := chip.MagneticField() // Read magnetic field, () → (float64 T, float64 T, float64 T, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("X=%.6f T  Y=%.6f T  Z=%.6f T\n", x, y, z)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
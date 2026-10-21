//go:build linux && !tinygo

// LIS3DH minimal example — Linux host.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x18"), 0, 8)
	if err != nil {
		panic(err)
	}
	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x18) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := accelerometer.NewLIS3DHMinimal(conn, uint8(addr)) // Create LIS3DH driver, (connection, addr=0x18) → (*LIS3DHMinimal, error)
	if err != nil {
		panic(err)
	}
	for {
		x, y, z, err := chip.Read() // Read acceleration, () → (float64 g, float64 g, float64 g, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("x=%.4f g y=%.4f g z=%.4f g\n", x, y, z)
		time.Sleep(100 * time.Millisecond)
	}
}

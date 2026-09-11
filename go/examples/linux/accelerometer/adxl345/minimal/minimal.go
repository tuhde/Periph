//go:build linux && !tinygo

// ADXL345 minimal example — Linux host.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x53"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x53) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	sensor, err := accelerometer.NewADXL345Minimal(conn, false) // Create ADXL345 driver, (conn, spi=false) → (*ADXL345Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		x, y, z, err := sensor.Read() // Read 3-axis acceleration, () → (float32, float32, float32, error) g, g, g
		if err != nil {
			panic(err)
		}
		fmt.Printf("x=%.3f y=%.3f z=%.3f g\n", x, y, z)
		time.Sleep(100 * time.Millisecond)
	}
}
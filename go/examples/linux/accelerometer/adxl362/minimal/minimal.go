//go:build linux && !tinygo

// ADXL362 minimal example — Linux host.
//
// Opens /dev/spidevB.D at 8 MHz and reads 3-axis acceleration in *g* once
// per second.
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
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewSPIConnection(bus, device, 8_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, max_speed=8 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := accelerometer.NewADXL362Minimal(conn) // Create ADXL362 driver, (connection) → (*ADXL362Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		x, y, z, err := chip.Read() // Read 3-axis acceleration, () → (x, y, z g, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "read:", err)
		} else {
			fmt.Printf("x=%+.3f  y=%+.3f  z=%+.3f g\n", x, y, z)
		}
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
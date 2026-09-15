//go:build linux && !tinygo

// LPS28DFW minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5C) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewLPS28DFWMinimal(conn) // Create LPS28DFW driver, (connection) → (*LPS28DFWMinimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 5; i++ {
		t, err := chip.ReadTemperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.ReadPressure() // Read pressure, () → (float32 hPa, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("%.1f C, %.1f hPa\n", t, p)
		time.Sleep(time.Second)
	}
}
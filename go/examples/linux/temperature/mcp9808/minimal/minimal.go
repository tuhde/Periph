//go:build linux && !tinygo

// MCP9808 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
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

	conn, err := connection.NewI2CConnection(bus, temperature.MCP9808I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x18) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	m, err := temperature.NewMCP9808Minimal(conn) // Create MCP9808 driver, (conn) → (*MCP9808Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		t, err := m.ReadTemperature() // Read ambient temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("temperature %.4f °C\n", t)
		time.Sleep(1000 * time.Millisecond)
	}
}

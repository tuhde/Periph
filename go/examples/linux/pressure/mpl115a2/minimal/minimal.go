//go:build linux && !tinygo

// MPL115A2 minimal example — Linux host.
//
// Constructs the driver, then reads pressure and temperature in a loop.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, 0x60, nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewMpl115a2Minimal(conn) // Create MPL115A2 driver, (connection) → (*Mpl115a2Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		p, err := chip.ReadPressure() // Read pressure, () → (float64 Pa, error)
		if err != nil {
			panic(err)
		}
		t, err := chip.ReadTemperature() // Read temperature, () → (float64 C approximate, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("P=%.0f Pa, T=%.1f C\n", p, t)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

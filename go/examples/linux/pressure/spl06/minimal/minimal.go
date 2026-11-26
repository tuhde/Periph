//go:build linux && !tinygo

// SPL06 minimal example — Linux host.
//
// Constructs the driver, then reads temperature and pressure every second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x77"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x77) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := pressure.NewSPL06Minimal(conn) // Create SPL06 driver, (connection) → (*SPL06Minimal, error)
	chk(err)
	chk(chip.Init()) // Check ID, read coefficients, apply defaults, () → error

	for {
		m, err := chip.Read() // Measure temperature and pressure, () → (SPL06Measurement {TemperatureC °C, PressurePa Pa}, error)
		chk(err)
		fmt.Printf("%.2f Pa, %.2f C\n", m.PressurePa, m.TemperatureC)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

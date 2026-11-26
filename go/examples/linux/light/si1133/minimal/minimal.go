//go:build linux && !tinygo

// Si1133 minimal example — Linux host.
//
// Constructs the driver, then prints the UV and white counts once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x55"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x55) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := light.NewSi1133Minimal(conn) // Create Si1133 driver, (connection) → (*Si1133Minimal, error)
	chk(err)
	chk(chip.Init()) // Check PART_ID, reset, UV + white channels, () → error

	for {
		m, err := chip.Read() // Forced measurement, () → (Si1133Reading {UV counts, White counts}, error)
		chk(err)
		fmt.Printf("UV %d, white %d\n", m.UV, m.White)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

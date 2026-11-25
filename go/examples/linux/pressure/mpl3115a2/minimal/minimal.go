//go:build linux && !tinygo

// MPL3115A2 minimal example — Linux host.
//
// Constructs the driver, then reads pressure and temperature every two seconds.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := pressure.NewMPL3115A2Minimal(conn) // Create MPL3115A2 driver, (connection) → *MPL3115A2Minimal
	chk(chip.Init())                           // Reset and apply defaults, () → error

	for {
		p, t, err := chip.Read() // Read pressure and temperature, () → (float32 Pa, float32 °C, error)
		chk(err)
		fmt.Printf("%.2f Pa, %.2f C\n", p, t)
		time.Sleep(2 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// BH1750 minimal example — Linux host.
//
// Constructs the driver, then measures the illuminance every second.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x23"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x23) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := light.NewBH1750Minimal(conn) // Create BH1750 driver, (connection) → *BH1750Minimal
	chk(chip.Init())                     // Power on, () → error

	for {
		lux, err := chip.Read() // Measure illuminance, () → (float32 lx, error)
		chk(err)
		fmt.Printf("%.1f lx\n", lux)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

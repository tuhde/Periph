//go:build linux && !tinygo

// Max17048 minimal example — Linux host.
//
// Constructs the driver, then reads cell voltage, state of charge and
// temperature every 5 seconds.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x36"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x36) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := power.NewMax17048Minimal(conn, 1) // Create MAX17048 driver, (connection, cells) → (*Max17048Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		v := must(chip.Voltage()) // Read cell voltage, () → (float32 V, error)
		p := must(chip.Percent()) // Read state of charge, () → (float32 %, error)
		fmt.Printf("%.3f V  %.1f %%\n", v, p)
		time.Sleep(time.Second)
	}
}

// must unwraps a (value, error) pair, panicking on error.
func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

// check panics on error.
func check(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

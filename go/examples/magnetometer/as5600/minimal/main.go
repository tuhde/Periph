//go:build linux && !tinygo

// AS5600 minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N and prints the absolute angle and
// raw scaled count in a loop.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x36) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := magnetometer.NewAs5600Minimal(tr) // Create AS5600 driver, (transport) → (*As5600Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		a, err := chip.Angle() // Read absolute angle, () → (float64 degrees, error)
		if err != nil {
			panic(err)
		}
		r, err := chip.AngleRaw() // Read scaled angle count, () → (uint16 0-4095, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("angle=%.2f deg  raw=%d\n", a, r)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

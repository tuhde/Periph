//go:build linux && !tinygo

// APDS-9960 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N transport and prints
// RGBC channel values once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/transport"
	"github.com/tuhde/Periph/go/periph/chips/light"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x39"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x39) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := light.NewAPDS9960Minimal(tr) // Create APDS-9960 driver, (transport) → (*APDS9960Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		c, r, g, b, err := chip.Color() // Read all four RGBC channels, () → (clear, red, green, blue uint16, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("C=%d R=%d G=%d B=%d\n", c, r, g, b)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

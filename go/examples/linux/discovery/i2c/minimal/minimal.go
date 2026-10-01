//go:build linux && !tinygo

// I²C discovery minimal example — Linux host.
//
// Scans /dev/i2c-N and prints what is connected.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/discovery"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	devices, err := discovery.Discover(bus, false) // Discover chips, (bus=1, active=false) → ([]Device, error)
	if err != nil {
		panic(err)
	}
	for _, d := range devices {
		if d.Identified != "" {
			fmt.Printf("0x%02X %s\n", d.Address, d.Identified)
		} else {
			fmt.Printf("0x%02X %v\n", d.Address, d.Candidates)
		}
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

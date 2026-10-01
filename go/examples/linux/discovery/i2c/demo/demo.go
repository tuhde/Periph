//go:build linux && !tinygo

// I²C discovery demo — Linux host.
//
// Takes inventory of an unknown bench setup and reports what each address is.
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

	// --- Take inventory of an unknown bench setup ---
	// Scan /dev/i2c-1 and name everything that answers. A chip is only named when
	// its identity register matches exactly one registry entry; shared addresses
	// without an ID register stay as candidate lists.
	devices, err := discovery.Discover(bus, false) // Discover chips, (bus=1, active=false) → ([]Device, error)
	if err != nil {
		panic(err)
	}

	// --- Report what was found ---
	// Identified: confirmed chip; Candidates: could be any of these; empty means the
	// address answers but the registry does not know it.
	skipped := 0
	for _, d := range devices {
		switch {
		case d.Identified != "":
			fmt.Printf("0x%02X  %s (driver: %s)\n", d.Address, d.Identified, d.Driver)
		case d.InUseByKernel:
			fmt.Printf("0x%02X  claimed by a kernel driver, could be %v\n", d.Address, d.Candidates)
		case len(d.Candidates) > 0:
			fmt.Printf("0x%02X  one of %v\n", d.Address, d.Candidates)
		default:
			fmt.Printf("0x%02X  unknown device\n", d.Address)
		}
		if d.ProbeSkipped == discovery.SkipWriteSensitive {
			skipped++
		}
	}

	// --- Flag addresses we deliberately did not probe ---
	// Some candidates (port expanders, DACs) treat a stray write as data, so the
	// identity probe is skipped unless discovery.Discover(bus, true) is used.
	fmt.Printf("%d address(es) not probed because a candidate is write-sensitive\n", skipped)
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

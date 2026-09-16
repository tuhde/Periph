//go:build linux && !tinygo

// ADE7953 hardware-in-loop test for the Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, _ := strconv.Atoi(envOr("I2C_BUS", "1"))
	addr64, _ := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)

	tr, err := connection.NewI2CConnection(bus, uint8(addr64), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer tr.Close()

	chip, err := power.NewADE7953Full(tr, 251.0, 30.0)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	if v, err := chip.Voltage(); err == nil {
		check("voltage non-negative", v >= 0.0)
	}
	if i, err := chip.Current(); err == nil {
		check("current non-negative", i >= 0.0)
	}
	if p, err := chip.ActivePower(); err == nil {
		check("activePower finite", p > -1.0e6)
	}
	if e, err := chip.ActiveEnergy(); err == nil {
		check("activeEnergy finite", e > -1000.0)
	}
	if lp, err := chip.LinePeriod(); err == nil {
		check("linePeriod positive", lp > 0.0)
	}

	if err := chip.Reset(); err == nil {
		if v, err := chip.Voltage(); err == nil {
			check("voltage after reset", v >= 0.0)
		}
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
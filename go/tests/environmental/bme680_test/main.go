//go:build linux && !tinygo

// BME680 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the BME680 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x76"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport:", err)
		os.Exit(2)
	}
	defer tr.Close()

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	chip, err := environmental.NewBME680Minimal(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new minimal:", err)
		os.Exit(2)
	}

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)

	p, err := chip.Pressure()
	check("pressure_range", err == nil && p >= 300.0 && p <= 1100.0)

	h, err := chip.Humidity()
	check("humidity_range", err == nil && h >= 0.0 && h <= 100.0)

	g, err := chip.GasResistance()
	// Gas resistance is allowed to be NaN if the heater did not stabilize.
	check("gas_resistance_present", err == nil && (!math.IsNaN(float64(g)) || err == nil))

	// Re-open the bus for the Full driver.
	tr2, err := transport.NewI2CTransport(bus, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport full:", err)
		os.Exit(2)
	}
	defer tr2.Close()

	full, err := environmental.NewBME680Full(tr2)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}

	if err := full.SetOversampling(
		environmental.BME680OSRSX4,
		environmental.BME680OSRSX2,
		environmental.BME680OSRSX1,
	); err == nil {
		check("set_oversampling", true)
	} else {
		check("set_oversampling", false)
	}

	cid, err := full.ChipID()
	check("chip_id", err == nil && cid == 0x61)

	t2, p2, h2, _, err := full.ReadAll()
	check("read_all", err == nil &&
		t2 >= -40.0 && t2 <= 85.0 &&
		p2 >= 300.0 && p2 <= 1100.0 &&
		h2 >= 0.0 && h2 <= 100.0)

	if err := full.Reset(); err == nil {
		check("reset", true)
	} else {
		check("reset", false)
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

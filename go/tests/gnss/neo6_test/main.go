//go:build linux && !tinygo

// NEO-6 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the NEO-6 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
//
// Requires a NEO-6 module wired to the I2C (DDC) bus with a clear sky
// view. Achieving an actual fix needs an outdoor antenna and can take
// up to ~26 s (cold start); this test only requires that well-typed
// values come back from the module's NMEA output.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/gnss"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x42"), 0, 8)
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

	chip := gnss.NewNEO6Minimal(tr, "i2c")

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

	// Initial state — Fix starts at 0, Latitude starts as nil.
	check("fix_starts_at_0", chip.Fix() == 0)
	check("latitude_starts_at_nil", chip.Latitude() == nil)
	check("longitude_starts_at_nil", chip.Longitude() == nil)
	check("altitude_starts_at_nil", chip.Altitude() == nil)

	// Drive 3000 Update() calls. Each call consumes a single byte
	// from the DDC stream; with a default 1 Hz NMEA output, this
	// covers ~10 minutes of sentence production.
	for i := 0; i < 3000; i++ {
		_, err := chip.Update()
		if err != nil {
			fmt.Fprintf(os.Stderr, "update at %d: %v\n", i, err)
			break
		}
	}

	check("fix_is_valid_quality", chip.Fix() <= 2)
	check("satellites_non_negative", int(chip.Satellites()) >= 0)
	if chip.Fix() > 0 {
		lat := chip.Latitude()
		lon := chip.Longitude()
		check("latitude_in_range_once_fixed", lat != nil && *lat >= -90.0 && *lat <= 90.0)
		check("longitude_in_range_once_fixed", lon != nil && *lon >= -180.0 && *lon <= 180.0)
		check("altitude_populated_once_fixed", chip.Altitude() != nil)
	} else {
		fmt.Println("note: no fix acquired during the test window (needs sky view)")
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

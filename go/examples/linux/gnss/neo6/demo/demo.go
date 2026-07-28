//go:build linux && !tinygo

// NEO-6 demo example — Linux host.
//
// Portable GPS logger. Reads NMEA position from the module over I2C
// (DDC), then prints fix data to the console. When the fix quality is
// 0 (no fix), prints a waiting indicator and the satellite count.
// Once a fix is acquired, prints UTC time, latitude (decimal degrees,
// 6 dp), longitude (decimal degrees, 6 dp), altitude (m, 1 dp),
// satellites, and HDOP. The loop runs for 60 iterations and then
// exits; this demonstrates cold-start wait time, the transition from
// no-fix to fix, and field-by-field extraction from NMEA GGA/RMC
// sentences.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gnss"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x42"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x42) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip := gnss.NewNEO6Full(conn, "i2c") // Create NEO-6 driver, (connection, bus_type="i2c") → *NEO6Full

	fmt.Printf("%-8s %-10s %-12s %-12s %-8s %-6s %-6s\n",
		"iter", "utc_time", "lat", "lon", "alt", "sats", "fix")

	for n := 0; n < 60; n++ {
		// --- Each tick must call Update within the 2-second idle window ---
		// The DDC bus drops all pending output if the host pauses
		// reading for 2 s; poll once per second to keep the buffer alive.
		_, err := chip.Update() // Read and parse one NMEA sentence, () → (bool, error)
		if err != nil {
			fmt.Fprintf(os.Stderr, "update err at %d: %v\n", n, err)
			time.Sleep(time.Second)
			continue
		}

		// --- Only surface coordinates once the module has a valid fix ---
		// gpsFix alone does not guarantee validity; the GGA fix-status
		// field must be > 0 (and the lat/lon fields non-empty) for the
		// driver to populate them.
		fix := chip.Fix()
		sats := chip.Satellites()
		lat := chip.Latitude()
		lon := chip.Longitude()
		alt := chip.Altitude()
		utc := chip.UTCTime()
		hdop := chip.HDOP()

		if fix > 0 && lat != nil && lon != nil {
			altStr := "n/a"
			if alt != nil {
				altStr = fmt.Sprintf("%.1f", *alt)
			}
			hdopStr := "n/a"
			if hdop != nil {
				hdopStr = fmt.Sprintf("%.2f", *hdop)
			}
			fmt.Printf("%-8d %-10s %-12.6f %-12.6f %-8s %-6d %-6d hdop=%s\n",
				n, utc, *lat, *lon, altStr, sats, fix, hdopStr)
		} else {
			// --- No fix yet: show the waiting state ---
			fmt.Printf("%-8d %-10s %-12s %-12s %-8s %-6d %-6d\n",
				n, "-", "-", "-", "-", sats, fix)
		}
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// APDS-9960 demo example — Linux host.
//
// Adaptive ambient light logger. The loop reads RGBC counts and
// computes an approximate lux value. When the clear channel
// approaches saturation (> 90% of max count), the integration time
// is shortened (ATIME increased) to prevent overflow.
//
// The scenario demonstrates adaptive gain control: it watches for
// saturation, adjusts ATIME, and re-initialises the ALS engine.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
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

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x39) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := light.NewAPDS9960Full(conn) // Create APDS-9960 full driver, (connection) → (*APDS9960Full, error)
	if err != nil {
		panic(err)
	}

	// --- Monitor ambient light with adaptive integration time ---
	// Start with the default 200 ms integration (ATIME=0xB6). When
	// the clear channel approaches saturation, halve the integration
	// time to prevent overflow.
	atime := uint8(0xB6)
	if err := chip.ConfigureALS(atime, 1); err != nil { // Configure ALS, (atime=0xB6, again=1) → error
		panic(err)
	}

	fmt.Printf("%-8s %-8s %-8s %-8s %-8s %-8s\n", "iter", "clear", "R", "G", "B", "lux~")

	for n := 0; n < 60; n++ {
		// --- Each reading requires waiting for AVALID to assert ---
		// The ALS engine sets AVALID when the integration cycle
		// completes (after ~200 ms with ATIME=0xB6).
		for {
			valid, err := chip.IsAlsValid() // Check AVALID, () → (bool, error)
			if err != nil {
				panic(err)
			}
			if valid {
				break
			}
			time.Sleep(10 * time.Millisecond)
		}

		c, r, g, b, err := chip.Color() // Read all four RGBC channels, () → (uint16×4, error)
		if err != nil {
			panic(err)
		}

		// --- Approximate lux from raw RGBC values ---
		// lux ≈ -0.32466 × R + 1.57837 × G + -0.73191 × B
		// These coefficients are valid for the default glass lens
		// of the SparkFun breakout and will vary with aperture.
		lux := -0.32466*float32(r) + 1.57837*float32(g) - 0.73191*float32(b)
		fmt.Printf("%-8d %-8d %-8d %-8d %-8d %-8.0f\n", n, c, r, g, b, lux) // Read all four RGBC channels, () → (uint16×4, error)

		// --- Adaptive integration: reduce time when saturated ---
		// If CPSAT is set, the chip is at the upper limit of its
		// measurement range. Shorten integration (raise ATIME) so
		// the next cycle collects fewer photons.
		saturated, err := chip.IsAlsSaturated() // Check CPSAT, () → (bool, error)
		if err != nil {
			panic(err)
		}
		if saturated && atime < 0xFE {
			atime = atime + (255-atime)/2
			if atime > 0xFE {
				atime = 0xFE
			}
			if err := chip.ConfigureALS(atime, 1); err != nil { // Configure ALS, (atime, again) → error
				panic(err)
			}
			fmt.Printf("[SATURATED — reducing integration time, ATIME=0x%02X]\n", atime)
			time.Sleep(250 * time.Millisecond)
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

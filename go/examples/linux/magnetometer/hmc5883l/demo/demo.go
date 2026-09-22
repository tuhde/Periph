//go:build linux && !tinygo

// HMC5883L demo example — Linux host.
//
// Electronic compass demo: reads magnetic field at 15 Hz, computes heading
// from X and Y axes, prints compass bearing, and warns if sensor is tilted
// (|Z| > 30 µT).
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1E"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x1E) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := magnetometer.NewHMC5883LFull(conn) // Create HMC5883L driver, (connection) → (*HMC5883LFull, error)
	if err != nil {
		panic(err)
	}

	// --- Configure for electronic compass ---
	// 8-sample averaging at 15 Hz suppresses noise; ±1.3 Ga gain covers Earth's field (~0.5 Ga).
	err = chip.Configure(15, 8, 1) // Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → error
	if err != nil {
		panic(err)
	}

	fmt.Println("Electronic compass demo — hold sensor flat, rotate horizontally")
	fmt.Println("Vertical mount warning: |Z| > 30 µT indicates tilt compensation needed")
	fmt.Println()

	// --- Sample and compute heading ---
	// User rotates the sensor horizontally; we compute heading from X/Y axes.
	// At n=5, user is prompted to tilt vertically to demonstrate Z-axis detection.
	for n := 0; n < 10; n++ {
		for {
			ready, err := chip.DataReady() // Check data ready, () → (bool, error)
			if err != nil {
				panic(err)
			}
			if ready {
				break
			}
			time.Sleep(time.Millisecond)
		}
		x, y, z, err := chip.MagneticField() // Read magnetic field, () → (float64 T, float64 T, float64 T, error)
		if err != nil {
			panic(err)
		}

		// --- Compute heading from X and Y ---
		if x != -1 && y != -1 { // check for overflow sentinel
			heading := math.Atan2(y, x) * 180 / math.Pi
			if heading < 0 {
				heading += 360
			}
			fmt.Printf("Heading: %.1f°\n", heading)
		}

		// --- Vertical mount detection ---
		if z != -1 && math.Abs(z) > 30e-6 {
			fmt.Printf("[TILT WARNING] Z=%.1f µT — tilt compensation needed\n", z*1e6)
		}

		if n == 4 {
			fmt.Println(">>> Now tilt sensor vertically <<<")
		}

		time.Sleep(500 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
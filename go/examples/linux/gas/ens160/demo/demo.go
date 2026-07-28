//go:build linux && !tinygo

// ENS160 demo example — Linux host.
//
// Indoor air-quality monitor: starts in STANDARD mode, polls the
// validity flag until it reaches OK, then enters a loop printing AQI,
// TVOC, and eCO2 with a human-readable AQI label every second. If
// external temperature and humidity are available the demo sets them
// as compensation to show improved accuracy.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

// aqiLabel maps the 1-5 UBA scale to a human-readable string.
func aqiLabel(aqi int) string {
	switch aqi {
	case 1:
		return "Excellent"
	case 2:
		return "Good"
	case 3:
		return "Moderate"
	case 4:
		return "Poor"
	case 5:
		return "Unhealthy"
	default:
		return "Unknown"
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x53"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x53) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := gas.NewENS160Full(conn) // Create ENS160 driver, (connection) → (*ENS160Full, error)
	if err != nil {
		panic(err)
	}

	// --- If you have an external T/RH sensor, set it here ---
	// The ENS160 has its own internal default of 25 C / 50 %RH; feeding
	// it real ambient values compensates for humidity sensitivity in
	// the MOX sensing elements.
	// chip.SetCompensation(22.5, 45.0) // Set T/RH compensation, (temp_celsius, rh_percent) → error

	fmt.Printf("%-8s %-8s %-12s %-12s %-12s\n", "Sample", "AQI", "Label", "TVOC (ppb)", "eCO2 (ppm)")
	for n := 0; n < 60; n++ {
		// --- Poll the validity flag until data is trustworthy ---
		// The ENS160 powers up in "Initial Start-up" (VALIDITY=2) for up
		// to 1 hour, then enters a 3-minute warm-up (VALIDITY=1) after
		// each idle period. The driver refuses to return air-quality
		// values until VALIDITY=0 so we wait for that here.
		status, err := chip.Status() // Read validity flag, () → (int, error)
		if err != nil {
			panic(err)
		}
		if status != gas.ValidityOK {
			fmt.Fprintf(os.Stderr, "Warming up (VALIDITY=%d), retrying...\n", status)
			time.Sleep(2 * time.Second)
			continue
		}

		aqi, tvoc, eco2, err := chip.ReadAirQuality() // Read air quality, () → (int, float32 ppb, float32 ppm, error)
		if err != nil {
			fmt.Fprintf(os.Stderr, "read: %v\n", err)
			time.Sleep(time.Second)
			continue
		}

		// --- AQI 1-5 maps to a UBA category ---
		// The UBA scale rates indoor air from "Excellent" (no
		// objections) to "Unhealthy" (ventilation required).  See
		// the spec for the TVOC/ppm breakpoints.
		fmt.Printf("%-8d %-8d %-12s %-12.0f %-12.0f\n", n, aqi, aqiLabel(aqi), tvoc, eco2)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

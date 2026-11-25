//go:build linux && !tinygo

// MPL115A2 demo example — Linux host.
//
// Barometer log: prints the coefficients, then reads the pressure every 2 s
// for 30 s (Pa, hPa, approximate altitude, raw counts) and the min/max.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, 0x60, nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewMpl115a2Full(conn) // Create MPL115A2 driver, (connection) → (*Mpl115a2Full, error)
	if err != nil {
		panic(err)
	}

	a0, b1, b2, c12 := chip.Coefficients() // Read cached coefficients, () → (a0, b1, b2, c12 float64)
	fmt.Printf("a0=%.3f b1=%.5f b2=%.5f c12=%.8f\n", a0, b1, b2, c12)

	// --- Barometer log ---
	pmin, pmax := 1e9, 0.0
	for n := 0; n < 15; n++ {
		pa, err := chip.ReadPressure() // Read pressure, () → (float64 Pa, error)
		if err != nil {
			panic(err)
		}
		hpa, err := chip.PressureHpa() // Read pressure, () → (float64 hPa, error)
		if err != nil {
			panic(err)
		}
		alt, err := chip.Altitude(101325.0) // Compute altitude, (seaLevelPa) → (float64 m, error)
		if err != nil {
			panic(err)
		}
		padc, tadc, err := chip.ReadRaw() // One conversion, () → (padc, tadc uint16, error)
		if err != nil {
			panic(err)
		}
		if pa < pmin {
			pmin = pa
		}
		if pa > pmax {
			pmax = pa
		}
		fmt.Printf("%ds: %.0f Pa, %.2f hPa, alt=%.1f m, raw=(%d, %d)\n", n*2, pa, hpa, alt, padc, tadc)
		time.Sleep(2 * time.Second)
	}
	fmt.Printf("Summary: min=%.0f Pa, max=%.0f Pa\n", pmin, pmax)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

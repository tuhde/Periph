//go:build linux && !tinygo

// MPL115A2 complete example — Linux host.
//
// Exercises every Mpl115a2Full method once.
package main

import (
	"fmt"
	"os"
	"strconv"

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

	a0, b1, b2, c12 := chip.Coefficients()            // Read cached coefficients, () → (a0, b1, b2, c12 float64)
	if err := chip.ReloadCoefficients(); err != nil { // Re-read coefficient block, () → error
		panic(err) // use after a power cycle
	}
	pa, tc, err := chip.Read() // One conversion, () → (pressurePa, temperatureC float64, error)
	if err != nil {
		panic(err)
	}
	padc, tadc, err := chip.ReadRaw() // One conversion, () → (padc, tadc uint16, error) 10-bit counts
	if err != nil {
		panic(err)
	}
	p := chip.Compensate(padc, tadc) // Compensate raw counts, (padc, tadc) → float64 Pa
	// pure function, no bus access
	hpa, err := chip.PressureHpa() // Read pressure, () → (float64 hPa, error)
	if err != nil {
		panic(err)
	}
	alt, err := chip.Altitude(101325.0) // Compute altitude, (seaLevelPa) → (float64 m, error)
	if err != nil {
		panic(err)
	}
	// barometric formula, informative
	fmt.Printf("a0=%.3f b1=%.5f b2=%.5f c12=%.8f\n", a0, b1, b2, c12)
	fmt.Printf("%.0f Pa, %.1f C, raw=(%d, %d), %.0f Pa, %.2f hPa, alt=%.1f m\n", pa, tc, padc, tadc, p, hpa, alt)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

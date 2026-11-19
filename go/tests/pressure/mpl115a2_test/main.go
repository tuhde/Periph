//go:build linux && !tinygo

// MPL115A2 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the MPL115A2 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
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
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, 0x60, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := pressure.NewMpl115a2Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

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

	p, err := chip.ReadPressure()
	check("pressure_range", err == nil && p >= 50000 && p <= 115000)
	t, err := chip.ReadTemperature()
	check("temperature_range", err == nil && t >= -40 && t <= 105)
	padc, tadc, err := chip.ReadRaw()
	check("raw_range", err == nil && padc <= 1023 && tadc <= 1023)
	c := chip.Compensate(padc, tadc)
	check("compensate_range", c >= 50000 && c <= 115000)
	pa, tc, err := chip.Read()
	check("read_both", err == nil && pa >= 50000 && pa <= 115000 && tc >= -40 && tc <= 105)
	hpa, err := chip.PressureHpa()
	check("pressure_hpa", err == nil && hpa >= 500 && hpa <= 1150)
	alt, err := chip.Altitude(101325.0)
	check("altitude", err == nil && alt >= -1000 && alt <= 6000)
	a0, _, _, _ := chip.Coefficients()
	check("coefficients", chip.ReloadCoefficients() == nil && a0 != 0)

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

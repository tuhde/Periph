//go:build linux && !tinygo

// LPS28DFW hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

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

	chip, err := pressure.NewLPS28DFWMinimal(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}

	t, err := chip.ReadTemperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)
	p, err := chip.ReadPressure()
	check("pressure_range", err == nil && p >= 260.0 && p <= 1260.0)

	chipFull, err := pressure.NewLPS28DFWFull(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init full:", err)
		os.Exit(2)
	}
	if err := chipFull.Configure(pressure.LPS28DFWODR100Hz, pressure.LPS28DFWAVG128,
		pressure.LPS28DFWFSMode2, pressure.LPS28DFWLFPFODROver9, false); err == nil {
		check("full_configure", true)
	} else {
		check("full_configure", false)
	}

	alt, err := chipFull.Altitude(1013.25)
	check("altitude_range", err == nil && alt >= -500.0 && alt <= 9000.0)

	cid, err := chipFull.ChipID()
	check("chip_id_or_no_chip", err == nil && (cid == 0xB4 || cid == 0x00))

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}
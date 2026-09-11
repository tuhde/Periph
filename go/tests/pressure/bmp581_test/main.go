//go:build linux && !tinygo

// BMP581 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the BMP581 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x46"), 0, 8)
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

	chip, err := pressure.NewBMP581Minimal(conn, false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new minimal:", err)
		os.Exit(2)
	}

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)

	p, err := chip.Pressure()
	check("pressure_range", err == nil && p >= 30000.0 && p <= 125000.0)

	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection full:", err)
		os.Exit(2)
	}
	defer conn2.Close()

	full, err := pressure.NewBMP581Full(conn2, false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}

	if err := full.Configure(0x1C, 0, 0, true); err == nil {
		check("configure", true)
	} else {
		check("configure", false)
	}

	if err := full.SetMode(pressure.BMP581ModeNormal); err == nil {
		check("set_mode", true)
	} else {
		check("set_mode", false)
	}

	alt, err := full.Altitude(101325.0)
	check("altitude_range", err == nil && alt >= -500.0 && alt <= 9000.0)

	cid, err := full.ChipID()
	check("chip_id", err == nil && cid == 0x50)

	if err := full.SoftwareReset(); err == nil {
		check("software_reset", true)
	} else {
		check("software_reset", false)
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
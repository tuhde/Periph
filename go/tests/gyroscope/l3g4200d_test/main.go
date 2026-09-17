//go:build linux && !tinygo

// L3G4200D hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the L3G4200D check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
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

	chip, err := gyroscope.NewL3G4200DMinimal(conn, false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new minimal:", err)
		os.Exit(2)
	}
	x, y, z, err := chip.AngularRate()
	check("angular_rate_x_range", err == nil && x >= -50.0 && x <= 50.0)
	check("angular_rate_y_range", err == nil && y >= -50.0 && y <= 50.0)
	check("angular_rate_z_range", err == nil && z >= -50.0 && z <= 50.0)

	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection full:", err)
		os.Exit(2)
	}
	defer conn2.Close()

	full, err := gyroscope.NewL3G4200DFull(conn2, false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}
	cid, err := full.WHOAMI()
	check("who_am_i", err == nil && cid == 0xD3)

	if err := full.Configure(gyroscope.L3G4200DODR200Hz, 0, gyroscope.L3G4200DFS500DPS); err == nil {
		check("configure", true)
	} else {
		check("configure", false)
	}

	tmp, err := full.Temperature()
	check("temperature_range", err == nil && tmp >= -50 && tmp <= 100)

	st, err := full.Status()
	check("status_readable", err == nil && st <= 0xFF)

	if err := full.SetFullScale(gyroscope.L3G4200DFS2000DPS); err == nil {
		check("set_full_scale_2000", true)
	} else {
		check("set_full_scale_2000", false)
	}

	if err := full.EnableFIFO(gyroscope.L3G4200DFIFOStream, 10); err == nil {
		check("enable_fifo", true)
	} else {
		check("enable_fifo", false)
	}
	samples, err := full.FIFOSamples()
	check("fifo_samples_in_range", err == nil && samples <= 31)

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

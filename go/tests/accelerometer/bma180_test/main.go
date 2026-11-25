//go:build linux && !tinygo

// BMA180 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
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

	minimal, err := accelerometer.NewBMA180Minimal(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init minimal:", err)
		os.Exit(2)
	}
	x, y, z, err := minimal.Read()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read:", err)
		os.Exit(2)
	}
	check("read_returns_three_floats", true)
	mag := (x*x + y*y + z*z)
	check("magnitude_near_1g", mag > 0.25 && mag < 2.25)

	full, err := accelerometer.NewBMA180Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init full:", err)
		os.Exit(2)
	}
	if err := full.SetRange(4); err != nil {
		fmt.Fprintln(os.Stderr, "set range:", err)
		os.Exit(2)
	}
	x, y, z, err = full.Read()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read full:", err)
		os.Exit(2)
	}
	check("read_after_set_range_4g", true)

	temp, err := full.ReadTemperature()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read temp:", err)
		os.Exit(2)
	}
	check("temperature_in_range", temp >= -40.0 && temp <= 87.5)

	al, ml, err := full.ReadVersion()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read version:", err)
		os.Exit(2)
	}
	check("read_version_ok", al <= 0x0F && ml <= 0x0F)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
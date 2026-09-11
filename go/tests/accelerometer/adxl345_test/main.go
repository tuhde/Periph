//go:build linux && !tinygo

// ADXL345 hardware test — Linux host.
package main

import (
	"fmt"
	"math"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x53"), 0, 8)
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

	sensor, err := accelerometer.NewADXL345Minimal(conn, false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("construct_minimal", true)

	x, y, z, err := sensor.Read()
	check("read_no_error", err == nil)
	check("read_returns_floats",
		!math.IsNaN(float64(x)) && !math.IsNaN(float64(y)) && !math.IsNaN(float64(z)))
	mag := float32(math.Sqrt(float64(x*x + y*y + z*z)))
	check("magnitude_near_1g", mag >= 0.5 && mag <= 1.5)

	full, err := accelerometer.NewADXL345Full(conn, false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init full:", err)
		os.Exit(2)
	}
	check("construct_full", true)
	if err := full.SetRange(4); err != nil {
		fmt.Fprintln(os.Stderr, "set_range:", err)
		os.Exit(2)
	}
	x, y, z, err = full.Read()
	check("read_after_set_range_4g",
		err == nil && !math.IsNaN(float64(x)) && !math.IsNaN(float64(y)) && !math.IsNaN(float64(z)))

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
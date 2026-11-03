//go:build linux && !tinygo

// MMA8451Q hardware test — Linux host.
//
// Opens /dev/i2c-N, exercises the MMA8451Q driver, prints PASS/FAIL lines,
// and exits with the standard ===DONE: ... === summary.
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
	if v, ok := os.LookupEnv(k); ok {
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1C"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus, addr, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := accelerometer.NewMMA8451QMinimal(conn) // Create MMA8451Q driver, (connection) → (*MMA8451QMinimal, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("construct_minimal", true)

	x, y, z, err := chip.Read() // Read 3-axis acceleration, () → (x, y, z g, error)
	check("read_no_error", err == nil)
	check("read_returns_floats",
		err == nil && !math.IsNaN(float64(x)) && !math.IsNaN(float64(y)) && !math.IsNaN(float64(z)))
	mag := float32(math.Sqrt(float64(x*x + y*y + z*z)))
	check("magnitude_near_1g", mag >= 0.5 && mag <= 1.5)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

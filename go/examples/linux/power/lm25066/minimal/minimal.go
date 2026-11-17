//go:build linux && !tinygo

// LM25066 minimal example — Linux host.
//
// Constructs the driver, then reads VIN, VOUT, input current, input power and
// MOSFET temperature every second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip := power.NewLM25066Minimal(conn, 1.0, false) // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh) → *LM25066Minimal

	for {
		v := must(chip.VoltageIn())   // Read VIN, () → (float64 V, error)
		o := must(chip.VoltageOut())  // Read VOUT, () → (float64 V, error)
		i := must(chip.Current())     // Read input current, () → (float64 A, error)
		p := must(chip.Power())       // Read input power, () → (float64 W, error)
		t := must(chip.Temperature()) // Read MOSFET temperature, () → (float64 °C, error)
		fmt.Printf("VIN %.3f V  VOUT %.3f V  IIN %.3f A  PIN %.2f W  T %.1f C\n", v, o, i, p, t)
		time.Sleep(1 * time.Second)
	}
}

// must unwraps a (value, error) pair, panicking on error.
func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

// check panics on error.
func check(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// LTC4151 minimal example — Linux host.
//
// Reads supply voltage, current and power once per second.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x6A"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x6A) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := power.NewLTC4151Minimal(conn, 0.02) // Create LTC4151 driver, (connection, rSense=0.02 Ω) → (*LTC4151Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		v, err := chip.Voltage() // Read supply voltage, () → (float32 V, error)
		if err != nil {
			panic(err)
		}
		c, err := chip.Current() // Read load current, () → (float32 A, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Power() // Read load power, () → (float32 W, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("V=%.3f V  I=%.4f A  P=%.3f W\n", v, c, p)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

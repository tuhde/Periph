//go:build linux && !tinygo

// AD5252 minimal example — Linux host.
//
// Sets channel 0 to a few wiper positions, printing the readback and the W–B
// resistance, then sets channel 1 to 2.2 kΩ.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
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

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, adcdac.AD5252I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x2C) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	pot, err := adcdac.NewAD5252Minimal(conn, adcdac.AD5252DefaultRAB, adcdac.AD5252DefaultRWiper) // Create AD5252 driver, (conn, rAB=10000.0 Ω, rWiper=75.0 Ω) → (*AD5252Minimal, error)
	must(err)
	for _, code := range []int{0, 64, 128, 192, 255} {
		must(pot.SetWiper(0, code)) // Set wiper position, (channel 0–1, code 0–255) → error
		w, err := pot.GetWiper(0)   // Read wiper position, (channel 0–1) → (uint8 0–255, error)
		must(err)
		r, err := pot.GetResistance(0) // Read W–B resistance, (channel 0–1) → (float32 Ω, error)
		must(err)
		fmt.Println(code, w, r)
		time.Sleep(500 * time.Millisecond)
	}
	must(pot.SetResistance(1, 2200.0)) // Set W–B resistance, (channel 0–1, ohms Ω) → error
}

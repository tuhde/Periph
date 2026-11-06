//go:build linux && !tinygo

// AD5248 minimal — Linux host.
//
// Constructs the driver and prints the wiper code and W-B resistance of channel 0 in a loop.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x2C"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x2C) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	pot := adcdac.NewAD5248Minimal(conn, 10000, 160) // Create AD5248 driver, (connection, rAB=10000 Ω, rWiper=160 Ω) → *AD5248Minimal

	if err := pot.SetWiper(0, 64); err != nil { // Set channel 0 wiper, (channel 0-1, code 0-255) → error
		panic(err)
	}
	if err := pot.SetResistance(1, 2200); err != nil { // Set channel 1 W-B resistance, (channel 0-1, ohms) → error
		panic(err)
	}

	for {
		code, err := pot.GetWiper(0) // Read channel 0 wiper, (channel 0-1) → (uint8 0-255, error)
		if err != nil {
			panic(err)
		}
		ohms, err := pot.GetResistance(0) // Read channel 0 W-B resistance, (channel 0-1) → (float64 Ω, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("ch0 code=%d  R_WB=%.0f ohm\n", code, ohms)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

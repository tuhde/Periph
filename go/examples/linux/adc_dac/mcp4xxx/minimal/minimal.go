//go:build linux && !tinygo

// MCP4XXX minimal — Linux host.
//
// Constructs the Minimal driver and prints the wiper code and W-B resistance of network 0 in a loop.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

func main() {
	part := envOr("MCP4XXX_PART", "MCP4532")
	defAddr, err := adcdac.MCP4XXXDefaultAddress(part)
	if err != nil {
		panic(err)
	}
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", fmt.Sprintf("%#x", defAddr)), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	pot, err := adcdac.NewMCP4XXXMinimal(conn, part, 10000, 75) // Create MCP4XXX driver, (connection, part="MCP4532", rAB=10000 Ω, rWiper=75 Ω) → (*MCP4XXXMinimal, error)
	if err != nil {
		panic(err)
	}
	if err := pot.Init(); err != nil { // Bus check, () → error
		panic(err)
	}

	if err := pot.SetWiper(32, 0); err != nil { // Set wiper, (code 0-128, channel=0) → error
		panic(err)
	}
	if err := pot.SetResistance(2200, 0); err != nil { // Set W-B resistance, (ohms, channel=0) → error
		panic(err)
	}

	for {
		code := must(pot.GetWiper(0))      // Read wiper, (channel=0) → (int 0-128, error)
		ohms := must(pot.GetResistance(0)) // Read W-B resistance, (channel=0) → (float64 Ω, error)
		fmt.Printf("code=%d  R_WB=%.0f ohm\n", code, ohms)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

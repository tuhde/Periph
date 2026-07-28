//go:build linux && !tinygo

// MCP4728 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection, then loops setting
// channel A to half-scale, channel B to mid-range, and updating all
// four channels simultaneously with [0.0, 0.25, 0.5, 1.0].
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := adcdac.NewMCP4728Minimal(conn) // Create MCP4728 driver, (connection) → (*MCP4728Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if err := chip.SetVoltage(0, 0.5); err != nil { // Set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → error
			panic(err)
		}
		if err := chip.SetRaw(1, 2048); err != nil { // Set channel B raw 12-bit code, (channel=0–3, code=0–4095) → error
			panic(err)
		}
		if err := chip.SetAll([4]float32{0.0, 0.25, 0.5, 1.0}); err != nil { // Update all four channels simultaneously, (fractions[4]) → error
			panic(err)
		}
		fmt.Println("MCP4728 minimal running")
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

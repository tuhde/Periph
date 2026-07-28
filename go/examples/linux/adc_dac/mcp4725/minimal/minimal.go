//go:build linux && !tinygo

// MCP4725 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection, then loops
// alternating the DAC between half-scale and three-quarter-scale.
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

	chip, err := adcdac.NewMCP4725Minimal(conn) // Create MCP4725 driver, (connection) → (*MCP4725Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if err := chip.SetVoltage(0.5); err != nil { // Set output as fraction of V_DD, (fraction=0.0–1.0) → error
			panic(err)
		}
		fmt.Println("set 0.5")
		time.Sleep(time.Second)
		if err := chip.SetRaw(2048); err != nil { // Set raw 12-bit code, (code=0–4095) → error
			panic(err)
		}
		fmt.Println("set raw 2048")
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

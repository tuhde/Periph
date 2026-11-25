//go:build linux && !tinygo

// MCP3422 minimal example — Linux host.
//
// Reads both differential inputs.
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

	conn, err := connection.NewI2CConnection(bus, adcdac.MCP3422I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	adc, err := adcdac.NewMCP3422Minimal(conn) // Create MCP3422 driver, (conn) → (*MCP3422Minimal, error)
	must(err)
	for i := 0; i < 10; i++ {
		raw, err := adc.ReadRaw(0) // Read a signed code, (channel 0–1) → (int32, error)
		must(err)
		v, err := adc.ReadVoltage(1) // Read the differential voltage, (channel 0–1) → (float32 V, error)
		must(err)
		fmt.Println("CH1 raw", raw, "CH2", v, "V")
		both, err := adc.ReadAll() // Read both channels, () → ([2]float32 V, error)
		must(err)
		fmt.Println(both[0], both[1])
		time.Sleep(time.Second)
	}
}

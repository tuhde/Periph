//go:build linux && !tinygo

// HY3131 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewSPIConnection(bus, device, 0, 1_000_000, nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := adcdac.NewHY3131Minimal(conn, 1.2) // Create HY3131 driver, (connection, vref=1.2 V) → (*HY3131Minimal, error)
	if err != nil {
		panic(err)
	}
	for {
		v, err := chip.ReadVoltage() // Read AD1 differential voltage, () → (float64 V, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("%.5f\n", v)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

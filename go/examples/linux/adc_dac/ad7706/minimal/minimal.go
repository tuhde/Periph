//go:build linux && !tinygo

// AD7706 minimal example — Linux host.
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

	conn, err := connection.NewSPIConnection(bus, device, 3, 5_000_000, nil, nil)   // Create SPI connection, (bus=0, device=0, mode=3, max_speed=5 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := adcdac.NewAD7706Minimal(conn, 2.5, adcdac.MCLK2_4576MHz, nil)         // Create AD7706 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nil) → (*AD7706Minimal, error)
	if err != nil {
		panic(err)
	}

	v, err := chip.ReadVoltage()                                                    // Read Channel 1 voltage, () → (float64, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("%.4f\n", v)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// ENS160 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N transport, then loops
// reading the AQI, TVOC, and eCO2 once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x53"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x53) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := gas.NewENS160Minimal(tr) // Create ENS160 driver, (transport) → (*ENS160Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		aqi, tvoc, eco2, err := chip.ReadAirQuality() // Read air quality, () → (int, float32 ppb, float32 ppm, error)
		if err != nil {
			// Warm-up errors are common in the first minute after
			// power-on; just report and keep polling.
			fmt.Fprintf(os.Stderr, "read: %v\n", err)
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("AQI=%d  TVOC=%.0f ppb  eCO2=%.0f ppm\n", aqi, tvoc, eco2)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// BME680 minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N, then reads temperature, pressure,
// humidity, and gas resistance in a loop using the datasheet's
// weather-monitoring preset (heater profile 0 at 320 °C / 150 ms).
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x76"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x76) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := environmental.NewBME680Minimal(tr) // Create BME680 driver, (transport) → (*BME680Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Pressure() // Read pressure, () → (float32 hPa, error)
		if err != nil {
			panic(err)
		}
		h, err := chip.Humidity() // Read humidity, () → (float32 %RH, error)
		if err != nil {
			panic(err)
		}
		g, err := chip.GasResistance() // Read gas resistance, () → (float32 Ω, error)
		if err != nil {
			panic(err)
		}
		if math.IsNaN(float64(g)) {
			fmt.Printf("T=%.2f C  P=%.2f hPa  H=%.2f %%RH  G=NaN\n", t, p, h)
		} else {
			fmt.Printf("T=%.2f C  P=%.2f hPa  H=%.2f %%RH  G=%.0f Ω\n", t, p, h, g)
		}
		time.Sleep(5 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

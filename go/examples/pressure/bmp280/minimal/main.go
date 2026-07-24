//go:build linux && !tinygo

// BMP280 minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N, then reads temperature and
// pressure in a loop using the datasheet's ultra-low-power preset.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	chip, err := pressure.NewBMP280Minimal(tr) // Create BMP280 driver, (transport) → (*BMP280Minimal, error)
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
		fmt.Printf("T=%.2f C  P=%.2f hPa\n", t, p)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

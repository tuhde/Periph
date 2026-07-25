//go:build linux && !tinygo

// BMP180 minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N, then reads temperature and
// pressure in a loop.
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

	tr, err := transport.NewI2CTransport(bus, 0x77) // Create I2C transport, (bus=1, addr=0x77) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := pressure.NewBmp180Minimal(tr) // Create BMP180 driver, (transport) → (*Bmp180Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, err := chip.Temperature() // Read temperature, () → (float64 C, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Pressure() // Read pressure, () → (float64 hPa, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("T=%.1f C, P=%.1f hPa\n", t, p)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

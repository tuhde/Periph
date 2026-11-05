//go:build linux && !tinygo

// ADS1115 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, adcdac.ADS1115I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	m, err := adcdac.NewADS1115Minimal(conn) // Create ADS1115 driver, (conn) → (*ADS1115Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		v, err := m.ReadVoltage(0) // Read voltage, (channel=0) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("voltage %v\n", v)
		time.Sleep(1000 * time.Millisecond)
	}
}

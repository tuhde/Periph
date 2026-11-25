//go:build linux && !tinygo

// ADS1013 minimal example — Linux host.
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

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, adcdac.ADS1013I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	adc, err := adcdac.NewADS1013Minimal(conn) // Create ADS1013 driver, (conn) → (*ADS1013Minimal, error)
	must(err)

	for i := 0; i < 10; i++ {
		raw, err := adc.ReadRaw() // Read raw code, () → (int16, error) (−2048…2047)
		must(err)
		v, err := adc.ReadVoltage() // Read voltage, () → (float32 V, error)
		must(err)
		fmt.Printf("raw %v\n", raw)
		fmt.Printf("voltage %.4f V\n", v)
		time.Sleep(1000 * time.Millisecond)
	}
}

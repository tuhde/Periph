//go:build linux && !tinygo

// ADS7828 minimal example — Linux host.
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

	conn, err := connection.NewI2CConnection(bus, adcdac.ADS7828I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	d, err := adcdac.NewADS7828Minimal(conn, 0) // Create ADS7828 driver, (conn, vref=0 → internal 2.5 V) → (*ADS7828Minimal, error)
	if err != nil {
		panic(err)
	}
	if err := d.Init(); err != nil { // Enable internal reference, () → error
		panic(err)
	}

	for i := 0; i < 10; i++ {
		raw, err := d.ReadRaw(0) // Read CH0 raw code, (channel=0–7) → (uint16, error)
		if err != nil {
			panic(err)
		}
		v0, err := d.ReadVoltage(0) // Read CH0 voltage, (channel=0–7) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		all, err := d.ReadAll() // Read all eight channels, () → ([8]float32 V, error)
		if err != nil {
			panic(err)
		}
		fmt.Println(raw, v0, all[7])
		time.Sleep(1000 * time.Millisecond)
	}
}

//go:build linux && !tinygo

// PCF8591 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection, then loops
// reading channel 0 and all four channels in single-ended mode.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x48"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := adcdac.NewPCF8591Minimal(conn) // Create PCF8591 driver, (connection) → (*PCF8591Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		ch0, err := chip.ReadChannel(0) // Read single channel, (channel=0–3) → (uint8, error)
		if err != nil {
			panic(err)
		}
		all, err := chip.ReadAll() // Read all four channels, () → ([4]uint8, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("PCF8591 minimal running ch0=%d all=%v\n", ch0, all)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// APDS-9930 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection and prints
// lux and proximity once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/chips/light"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x39"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x39) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := light.NewAPDS9930Minimal(conn) // Create APDS-9930 driver, (connection) → (*APDS9930Minimal, error)
	if err != nil {                              // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20
		panic(err)
	}

	for {
		lx, err := chip.Lux() // Read ambient illuminance, () → (float64 lx, error)
		if err != nil {        // IR-compensated lux via Ch0/Ch1 difference
			panic(err)
		}
		p, err := chip.Proximity() // Read proximity count, () → (uint16 count, error)
		if err != nil {            // 16-bit ADC value; higher = closer object
			panic(err)
		}
		fmt.Printf("lux=%.1f lx  proximity=%d\n", lx, p)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
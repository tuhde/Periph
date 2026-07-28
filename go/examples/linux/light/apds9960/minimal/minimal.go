//go:build linux && !tinygo

// APDS-9960 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection and prints
// RGBC channel values once per second.
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

	chip, err := light.NewAPDS9960Minimal(conn) // Create APDS-9960 driver, (connection) → (*APDS9960Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		c, r, g, b, err := chip.Color() // Read all four RGBC channels, () → (clear, red, green, blue uint16, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("C=%d R=%d G=%d B=%d\n", c, r, g, b)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

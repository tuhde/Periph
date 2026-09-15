//go:build linux && !tinygo

// MPR121 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection and prints the
// 12-bit touch bitmask once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/chips/other"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5A"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5A) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := other.NewMPR121Minimal(conn) // Create MPR121 driver, (connection) → (*MPR121Minimal, error)
	if err != nil {                            // resets, applies default thresholds (T=12, R=6), enters Run Mode on all 12 electrodes
		panic(err)
	}

	for {
		t, err := chip.Touched() // Read 12-bit touch bitmask, () → (uint16 bitmask, error)
		if err != nil {          // bit n=1 means ELEn is currently touched
			panic(err)
		}
		fmt.Printf("touched=0x%03X\n", t)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

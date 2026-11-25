//go:build linux && !tinygo

// MB85RC minimal example — Linux host.
//
// Writes an incrementing byte to address 0 and reads it back in a loop.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x50"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnectionWithWidth(bus, uint8(addr), 2, nil, nil) // Create I2C connection, (bus=1, addr=0x50, regBytes=2) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := memory.NewMB85RCMinimal(conn) // Create MB85RC driver, (connection) → (*MB85RCMinimal, error)
	if err != nil {
		panic(err)
	}

	for counter := byte(0); ; counter++ {
		if err := chip.WriteUserByte(0x0000, counter); err != nil { // Write a single byte, (address 0–32767, value 0–255) → error
			panic(err)
		}
		v, err := chip.ReadUserByte(0x0000) // Read a single byte, (address 0–32767) → (byte, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("Stored: %d\n", v)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

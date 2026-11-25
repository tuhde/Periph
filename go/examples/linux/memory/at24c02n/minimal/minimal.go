//go:build linux && !tinygo

// AT24C02N minimal example — Linux host.
//
// Constructs the driver, then writes an incrementing byte to address 0 and reads it back once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x50"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x50) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := memory.NewAT24C02NMinimal(conn) // Create AT24C02N driver, (connection) → (*AT24C02NMinimal, error)
	chk(err)

	counter := byte(0)
	for {
		chk(chip.WriteByteAt(0x0000, counter)) // Write a single byte, (address 0–255, value 0–255) → error
		v, err := chip.ReadByteAt(0x0000)      // Read a single byte, (address 0–255) → (byte, error)
		chk(err)
		fmt.Println("Stored:", v)
		counter++
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

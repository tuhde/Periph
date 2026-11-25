//go:build linux && !tinygo

// CAT24C512 minimal example — Linux host.
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

	conn, err := connection.NewI2CConnectionWithWidth(bus, uint8(addr), 2, nil, nil) // Create I2C connection, (bus=1, addr=0x50, regBytes=2) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := memory.NewCAT24C512Minimal(conn) // Create CAT24C512 driver, (connection) → (*CAT24C512Minimal, error)
	chk(err)

	counter := byte(0)
	for {
		chk(chip.WriteByteAt(0x0000, counter)) // Write a single byte, (address 0–65535, value 0–255) → error
		v, err := chip.ReadByteAt(0x0000)      // Read a single byte, (address 0–65535) → (byte, error)
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

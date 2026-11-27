//go:build linux && !tinygo

// PN532 minimal example — Linux host.
//
// Constructs the driver, then prints the UID of a card once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x24"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x24) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := rfid.NewPN532Minimal(conn) // Create PN532 driver, (connection) → (*PN532Minimal, error)
	chk(err)
	chk(chip.Init()) // Wake, check firmware, SAM normal mode, finite retries, () → error

	for {
		uid, err := chip.ReadUID(time.Second) // Detect a type A card, (timeout=1 s) → ([]byte | nil, error)
		chk(err)
		fmt.Printf("UID %X\n", uid)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

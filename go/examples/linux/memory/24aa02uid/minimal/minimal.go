//go:build linux && !tinygo

// 24AA02UID minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection, then loops
// reading the 32-bit factory serial number and printing it as hex.
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

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x50) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := memory.NewEEPROM24AA02UIDMinimal(conn) // Create 24AA02UID driver, (connection) → (*EEPROM24AA02UIDMinimal, error)
	if err != nil {
		panic(err)
	}

	for {
		uid, err := chip.ReadUID() // Read 32-bit unique serial number, () → ([4]byte, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("UID: %02X%02X%02X%02X\n", uid[0], uid[1], uid[2], uid[3])
		time.Sleep(2 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

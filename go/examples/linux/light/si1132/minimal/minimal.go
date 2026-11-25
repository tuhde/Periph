//go:build linux && !tinygo

// Si1132 minimal example — Linux host.
//
// Reads the UV index and the visible and infrared counts every second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := light.NewSi1132Minimal(conn) // Create Si1132 driver, (connection) → *Si1132Minimal
	chk(chip.Init())                     // Verify PART_ID and start forced mode, () → error

	for {
		r, v, ir, err := chip.Read() // Read UV index and ALS counts, () → (float32 UV index, uint16 visible counts, uint16 infrared counts, error)
		chk(err)
		fmt.Printf("UV %.2f, visible %d, infrared %d\n", r, v, ir)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

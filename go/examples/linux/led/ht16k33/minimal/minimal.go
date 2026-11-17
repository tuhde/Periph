//go:build linux && !tinygo

// HT16K33 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x70"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x70) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	matrix, err := led.NewHT16K33Minimal(conn) // Create HT16K33 driver, (connection) → (*HT16K33Minimal, error)
	if err != nil {
		panic(err)
	}

	for row := uint8(0); row < led.HT16K33Rows; row++ {
		if err := matrix.SetPixel(0, row, true); err != nil { // Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → error
			panic(err)
		}
	}
	if err := matrix.Show(); err != nil { // Write the shadow RAM to the chip, () → error
		panic(err)
	}
	time.Sleep(time.Second)
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

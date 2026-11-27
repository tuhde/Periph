//go:build linux && !tinygo

// Si1145 minimal example — Linux host.
//
// Constructs the driver, then prints proximity, UV index and light once per second.
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

var _ = fmt.Sprint
var _ = time.Second

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := light.NewSi1145Minimal(conn) // Create Si1145 driver, (connection) → (*Si1145Minimal, error)
	chk(err)
	chk(chip.Init()) // Check PART_ID, HW_KEY, UV + ALS + PS1, () → error

	for {
		r, err := chip.Read() // Forced PS + ALS, () → (Si1145Reading {Proximity, UVIndex, Visible, Infrared}, error)
		chk(err)
		fmt.Printf("prox %d  UV %.2f  vis %d  ir %d\n", r.Proximity, r.UVIndex, r.Visible, r.Infrared)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

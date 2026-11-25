//go:build linux && !tinygo

// MS5611 minimal example — Linux host.
//
// Reads temperature and pressure every second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x77"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x77) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := pressure.NewMS5611Minimal(conn) // Create MS5611 driver, (connection) → *MS5611Minimal
	chk(chip.Init())                        // Reset, read PROM, verify CRC-4, () → error
	for {
		t, p, err := chip.Read() // Read temperature and pressure, () → (float32 °C, float32 Pa, error)
		chk(err)                 // OSR 4096, second-order compensation applied
		fmt.Printf("%.2f C, %.1f Pa\n", t, p)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

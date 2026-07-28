//go:build linux && !tinygo

// RDA5807M minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N at 100.0 MHz, then loops seeking to
// the next station every 3 seconds.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x10"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x10) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	fm, err := comms.NewRda5807mMinimal(conn, 100.0, 8) // Create RDA5807M driver, (connection, frequency_mhz=100.0, volume=8) → (*Rda5807mMinimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if freq, err := fm.Seek(true); err != nil { // Seek to next station, (up=true) → (*float64 MHz, error)
			panic(err)
		} else if freq != nil {
			fmt.Printf("%.2f MHz\n", *freq)
		}
		time.Sleep(3 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

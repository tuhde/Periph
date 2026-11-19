//go:build linux && !tinygo

// AS7262 minimal example — Linux host.
//
// Reads the six calibrated channels and the temperature every two seconds.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/color"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x49"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x49) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := color.NewAS7262Minimal(conn) // Create AS7262 driver, (connection) → (*AS7262Minimal, error)
	chk(err)

	for {
		c, err := chip.Read() // Read six calibrated channels, () → (AS7262Channels[float32], error)
		chk(err)
		fmt.Printf("V %.1f B %.1f G %.1f Y %.1f O %.1f R %.1f\n", c.Violet, c.Blue, c.Green, c.Yellow, c.Orange, c.Red)
		t, err := chip.Temperature() // Read device temperature, () → (float32 °C, error)
		chk(err)
		fmt.Printf("%.0f C\n", t)
		time.Sleep(2 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

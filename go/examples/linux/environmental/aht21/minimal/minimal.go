//go:build linux && !tinygo

// AHT21 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection, then loops reading
// temperature and humidity once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := environmental.NewAHT21Minimal(conn) // Create AHT21 driver, (connection) → (*AHT21Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, h, err := chip.Read() // Trigger measurement, () → (float32 °C, float32 %RH, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("T=%.2f C  H=%.2f %%RH\n", t, h)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

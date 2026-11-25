//go:build linux && !tinygo

// SHT31 minimal example — Linux host.
//
// Constructs the driver, then reads temperature and humidity every two seconds.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x44"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x44) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := humidity.NewSHT31Minimal(conn) // Create SHT31 driver, (connection) → (*SHT31Minimal, error)
	chk(err)

	for {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
		chk(err)
		fmt.Printf("%.2f C, %.1f %%RH\n", t, rh)
		time.Sleep(2 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// HTS221 minimal example — Linux host.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5F"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5F) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := humidity.NewHTS221Minimal(conn) // Create HTS221 driver, (connection) → *HTS221Minimal
	chk(chip.Init())                        // Read calibration and start one-shot mode, () → error

	for {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %rH, error)
		chk(err)
		fmt.Printf("%.2f C, %.1f %%rH\n", t, rh)
		time.Sleep(2 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

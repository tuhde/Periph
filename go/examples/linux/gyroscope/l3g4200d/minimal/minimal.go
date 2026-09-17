//go:build linux && !tinygo

// L3G4200D minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N, then reads X/Y/Z angular rate in
// a loop using the chip's default 100 Hz ODR and ±250 dps full scale.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := gyroscope.NewL3G4200DMinimal(conn, false) // Create L3G4200D driver, (connection) → (*L3G4200DMinimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		x, y, z, err := chip.AngularRate() // Read X/Y/Z angular rate, () → (float32, float32, float32) rad/s
		if err != nil {
			panic(err)
		}
		fmt.Printf("X=%.3f Y=%.3f Z=%.3f rad/s\n", x, y, z)
		time.Sleep(100 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

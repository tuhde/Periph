//go:build linux && !tinygo

// BNO055 minimal example — Linux host.
//
// Constructs the driver, then loops reading the fused Euler angles ten times per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x29"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x29) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	chip, err := imu.NewBNO055Minimal(conn) // Create BNO055 driver, (connection) → (*BNO055Minimal, error)
	must(err)

	for {
		h, r, p, err := chip.Euler() // Read fused Euler angles, () → (float32, float32, float32 rad, error)
		must(err)
		fmt.Printf("heading %.2f rad, roll %.2f, pitch %.2f\n", h, r, p)
		time.Sleep(100 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

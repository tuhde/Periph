//go:build linux && !tinygo

// FXOS8700 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1E"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x1E) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	chip, err := accelerometer.NewFXOS8700Minimal(conn) // Create FXOS8700 driver, (connection) → (*FXOS8700Minimal, error)
	must(err)
	for {
		ax, ay, az, err := chip.ReadAccel() // Read acceleration, () → (float32 g, float32 g, float32 g, error)
		must(err)
		mx, my, mz, err := chip.ReadMag() // Read magnetic field, () → (float32 µT, float32 µT, float32 µT, error)
		must(err)
		fmt.Printf("accel x=%.4f y=%.4f z=%.4f g\n", ax, ay, az)
		fmt.Printf("mag   x=%.2f y=%.2f z=%.2f uT\n", mx, my, mz)
		time.Sleep(time.Second)
	}
}

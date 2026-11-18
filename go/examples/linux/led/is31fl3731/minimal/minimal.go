//go:build linux && !tinygo

// IS31FL3731 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x74"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x74) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	matrix, err := led.NewIs31fl3731Minimal(conn) // Create IS31FL3731 driver, (connection) → (*Is31fl3731Minimal, error)
	if err != nil {
		panic(err)
	}

	for col := uint8(0); col < led.Is31fl3731Cols; col++ {
		must(matrix.SetPixel(0, col, 128)) // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → error
	}
	time.Sleep(time.Second)
	must(matrix.Fill(32)) // Set every LED's PWM brightness, (brightness 0–255) → error
	time.Sleep(time.Second)
	must(matrix.Clear()) // Switch all LEDs off, () → error
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

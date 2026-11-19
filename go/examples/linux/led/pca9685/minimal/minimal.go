//go:build linux && !tinygo

// PCA9685 minimal example — Linux host.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint
var _ = math.Pi

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	pwm, err := led.NewPca9685Minimal(conn) // Create PCA9685 driver at 1 kHz, (connection) → (*Pca9685Minimal, error)
	must(err)

	for step := 0; step <= 10; step++ {
		must(pwm.SetDuty(0, float64(step)/10)) // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → error
		time.Sleep(200 * time.Millisecond)
	}
	must(pwm.SetDuty(0, 0))
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

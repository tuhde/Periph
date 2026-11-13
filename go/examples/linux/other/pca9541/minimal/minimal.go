//go:build linux && !tinygo

// PCA9541 minimal example — Linux host.
//
// Takes and releases the downstream I²C bus.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/other"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, other.PCA9541I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x70) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	selector, err := other.NewPCA9541Minimal(conn) // Create PCA9541 driver, (conn) → (*PCA9541Minimal, error)
	must(err)
	has, err := selector.HasControl() // Report control, () → (bool, error)
	must(err)
	fmt.Println("has control", has)
	ok, err := selector.Acquire(false, 20) // Take the downstream bus, (busInit bool, timeoutMs int) → (bool, error)
	must(err)
	fmt.Println("acquire", ok)
	time.Sleep(time.Second)
	must(selector.Release()) // Give the bus to the other master, () → error
}

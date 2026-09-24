//go:build linux && !tinygo

// VL53L0X minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/tof"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, tof.VL53L0XI2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x29) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	m, err := tof.NewVL53L0XMinimal(conn) // Create VL53L0X driver, (conn) → (*VL53L0XMinimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 50; i++ {
		d, err := m.Distance() // Measure distance, () → (uint16 mm, error)
		if err != nil {
			panic(err)
		}
		if m.RangeValid() { // Check last measurement, () → bool
			fmt.Printf("%d mm\n", d)
		} else {
			fmt.Println("out of range")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

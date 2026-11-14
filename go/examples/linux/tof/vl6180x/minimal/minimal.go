//go:build linux && !tinygo

// VL6180X minimal example — Linux host.
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

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnectionWithWidth(bus, tof.VL6180XI2CAddress, 2, nil, nil) // Create I2C connection, (bus=1, addr=0x29, regBytes=2) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	m, err := tof.NewVL6180XMinimal(conn) // Create VL6180X driver, (conn) → (*VL6180XMinimal, error)
	must(err)

	for i := 0; i < 50; i++ {
		mm, err := m.ReadRange() // Single-shot range, () → (uint8 mm, error)
		if err != nil {
			fmt.Println("range error", m.LastError()) // Last range error code, () → uint8
		} else {
			fmt.Println("mm", mm)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

//go:build linux && !tinygo

// DRV8830 minimal example — Linux host.
package main

import (
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
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

	conn, err := connection.NewI2CConnection(bus, motor.DRV8830I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	m, err := motor.NewDRV8830Minimal(conn) // Create DRV8830 driver, (conn) → (*DRV8830Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 5; i++ {
		if err := m.Drive(3.0); err != nil { // Drive at regulated voltage, (voltage V, + = forward) → error
			panic(err)
		}
		time.Sleep(2 * time.Second)
		if err := m.Drive(-3.0); err != nil { // Drive at regulated voltage, (voltage V, - = reverse) → error
			panic(err)
		}
		time.Sleep(2 * time.Second)
	}

	if err := m.Stop(); err != nil { // Coast to standby, () → error
		panic(err)
	}
}

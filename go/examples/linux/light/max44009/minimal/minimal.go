//go:build linux && !tinygo

// MAX44009 minimal example — Linux host.
//
// Reads the ambient illuminance once a second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
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

	conn, err := connection.NewI2CConnection(bus, light.MAX44009I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x4A) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	sensor, err := light.NewMAX44009Minimal(conn) // Create MAX44009 driver, (conn) → (*MAX44009Minimal, error)
	must(err)
	for i := 0; i < 10; i++ {
		lx, err := sensor.Lux() // Read illuminance, () → (float32 lux, error)
		must(err)
		fmt.Println(lx, "lx")
		time.Sleep(time.Second)
	}
}

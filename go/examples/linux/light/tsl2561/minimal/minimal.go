//go:build linux && !tinygo

// TSL2561 minimal example — Linux host.
//
// Reads the illuminance once a second (1× gain, 402 ms, T/FN/CL coefficients).
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

	conn, err := connection.NewI2CConnection(bus, light.TSL2561I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x39) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	sensor, err := light.NewTSL2561Minimal(conn) // Create TSL2561 driver, (conn) → (*TSL2561Minimal, error)
	must(err)
	for i := 0; i < 10; i++ {
		lx, err := sensor.ReadLux() // Read illuminance, () → (float32 lux, error)
		must(err)
		fmt.Println(lx, "lx")
		time.Sleep(time.Second)
	}
}

//go:build linux && !tinygo

// ISL29125 minimal example — Linux host.
//
// Prints the red, green and blue counts once a second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/color"
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

	conn, err := connection.NewI2CConnection(bus, color.ISL29125I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x44) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	sensor, err := color.NewISL29125Minimal(conn) // Create ISL29125 driver, (conn) → (*ISL29125Minimal, error)
	must(err)                                     // checks DEVICE_ID, starts RGB conversion
	for i := 0; i < 10; i++ {
		c, err := sensor.Read() // Read the three channels, () → (ISL29125Counts{Red, Green, Blue}, error)
		must(err)
		fmt.Println("R", c.Red, "G", c.Green, "B", c.Blue)
		time.Sleep(time.Second)
	}
}

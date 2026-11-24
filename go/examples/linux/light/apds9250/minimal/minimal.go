//go:build linux && !tinygo

// APDS-9250 minimal example — Linux host.
//
// Prints the red, green, blue and IR counts once a second.
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

	conn, err := connection.NewI2CConnection(bus, light.APDS9250I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x52) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	sensor, err := light.NewAPDS9250Minimal(conn) // Create APDS-9250 driver, (conn) → (*APDS9250Minimal, error)
	must(err)                                     // checks PART_ID, starts colour mode
	for i := 0; i < 10; i++ {
		c, err := sensor.Read() // Read the four channels, () → (APDS9250Counts{Red, Green, Blue, IR}, error)
		must(err)
		fmt.Println("R", c.Red, "G", c.Green, "B", c.Blue, "IR", c.IR)
		time.Sleep(time.Second)
	}
}

//go:build linux && !tinygo

// SAA1064 minimal example — Linux host.
//
// Counts 0–99 on the four digits.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	display, err := led.NewSAA1064Minimal(conn) // Create SAA1064 driver, (connection) → (*SAA1064Minimal, error)
	must(err)

	for n := 0; n < 100; n++ {
		must(display.ShowNumber(n)) // Show a right-aligned number, (value 0–9999) → error
		time.Sleep(100 * time.Millisecond)
	}
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

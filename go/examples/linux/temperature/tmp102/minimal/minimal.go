//go:build linux && !tinygo

// TMP102 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
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

func report(label string, v float32) { fmt.Printf("%s %.4f\n", label, v) }

func sleep(ms int) { time.Sleep(time.Duration(ms) * time.Millisecond) }

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x48"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	m := temperature.NewTMP102Minimal(conn) // Create TMP102 driver, (conn) → *TMP102Minimal

	for i := 0; i < 10; i++ {
		t, err := m.ReadTemperature() // Read temperature, () → (float32 °C, error)
		must(err)
		report("temperature", t)
		sleep(1000)
	}
}

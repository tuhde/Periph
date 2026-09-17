//go:build linux && !tinygo

// ADE7953 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, _ := strconv.Atoi(envOr("I2C_BUS", "1"))
	addr64, _ := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)

	conn, err := connection.NewI2CConnection(bus, uint8(addr64), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := power.NewADE7953Minimal(conn, 251.0, 30.0) // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V) → (*ADE7953Minimal, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}

	for i := 0; i < 10; i++ {
		v, err := chip.Voltage() // Read bus voltage, () → (float64 V, error)
		if err != nil {
			panic(err)
		}
		a, err := chip.Current() // Read load current, () → (float64 A, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.ActivePower() // Read active power, () → (float64 W, error)
		if err != nil {
			panic(err)
		}
		e, err := chip.ActiveEnergy() // Read active energy, () → (float64 Wh, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("V=%.2f I=%.3f P=%.2f E=%.4f\n", v, a, p, e)
		time.Sleep(1 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
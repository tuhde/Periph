//go:build linux && !tinygo

// INA3221 minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N, then reads bus voltage, current,
// and power for all three channels once per second.
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := power.NewINA3221Minimal(conn, 0.1) // Create INA3221 driver, (connection, r_shunt=0.1 Ω) → (*INA3221Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		for ch := uint8(1); ch <= 3; ch++ {
			v, err := chip.Voltage(ch) // Read bus voltage, (channel 1–3) → (float32 V, error)
			if err != nil {
				panic(err)
			}
			c, err := chip.Current(ch) // Read load current, (channel 1–3) → (float32 A, error)
			if err != nil {
				panic(err)
			}
			p, err := chip.Power(ch) // Read load power, (channel 1–3) → (float32 W, error)
			if err != nil {
				panic(err)
			}
			fmt.Printf("CH%d: V=%.3f V  I=%.4f A  P=%.4f W\n", ch, v, c, p)
		}
		fmt.Println()
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

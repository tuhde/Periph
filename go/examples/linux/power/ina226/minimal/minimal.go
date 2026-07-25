//go:build linux && !tinygo

// INA226 minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N, then reads bus voltage, current,
// and power once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x40) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := power.NewINA226Minimal(tr, 0.1, 2.0) // Create INA226 driver, (transport, r_shunt=0.1 Ω, max_current=2.0 A) → (*INA226Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		v, err := chip.Voltage() // Read bus voltage, () → (float32 V, error)
		if err != nil {
			panic(err)
		}
		c, err := chip.Current() // Read load current, () → (float32 A, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Power() // Read load power, () → (float32 W, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("V=%.3f V  I=%.4f A  P=%.4f W\n", v, c, p)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

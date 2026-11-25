//go:build linux && !tinygo

// DS3502 minimal example — Linux host.
//
// Steps the wiper through a few positions (RH = 15 V, RL = 0 V), printing the
// readback and the unloaded output voltage, then sets 6.0 V.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
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

	conn, err := connection.NewI2CConnection(bus, adcdac.DS3502I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x28) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	pot, err := adcdac.NewDS3502Minimal(conn, 15.0, 0.0) // Create DS3502 driver, (conn, vHigh=1.0 V, vLow=0.0 V) → (*DS3502Minimal, error)
	must(err)
	for _, code := range []int{0, 32, 64, 96, 127} {
		must(pot.SetWiper(code)) // Set wiper position, (code 0–127) → error
		w, err := pot.GetWiper() // Read wiper position, () → (uint8 0–127, error)
		must(err)
		v, err := pot.GetVoltage() // Read unloaded RW voltage, () → (float32 V, error)
		must(err)
		fmt.Println(code, w, v)
		time.Sleep(500 * time.Millisecond)
	}
	must(pot.SetVoltage(6.0)) // Set RW output voltage, (volts V) → error
}

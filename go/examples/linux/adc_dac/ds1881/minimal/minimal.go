//go:build linux && !tinygo

// DS1881 minimal example — Linux host.
//
// Set the volume of two audio channels.
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

	conn, err := connection.NewI2CConnection(bus, adcdac.DS1881I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x2C) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	amp, err := adcdac.NewDS1881Minimal(conn) // Create DS1881 driver, (conn) → (*DS1881Minimal, error)
	must(err)
	must(amp.Init()) // Apply defaults, () → error
	for _, db := range []float32{0, 10, 20, 40} {
		must(amp.SetAttenuation(0, db)) // Set attenuation, (channel 0–1, db dB) → error
		must(amp.SetAttenuation(1, db)) // Set attenuation, (channel 0–1, db dB) → error
		a, err := amp.GetAttenuation(0) // Read attenuation, (channel 0–1) → (float32 dB, error)
		must(err)
		fmt.Println(db, a)
		time.Sleep(500 * time.Millisecond)
	}
	must(amp.Mute(0)) // Mute channel, (channel 0–1) → error
}

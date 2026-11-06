//go:build linux && !tinygo

// DRV2605 minimal example — Linux host.
package main

import (
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
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

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, motor.DRV2605I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x5A) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	h, err := motor.NewDRV2605Minimal(conn, false) // Create DRV2605 driver, (conn, lra=false) → (*DRV2605Minimal, error)
	must(err)

	for _, effect := range []uint8{motor.DRV2605EffectStrongClick, motor.DRV2605EffectDoubleClick, motor.DRV2605EffectBuzz} {
		must(h.PlayEffect(effect)) // Play a ROM effect, (effectID 1–123) → error
		for {
			playing, err := h.IsPlaying() // Check playback, () → (bool, error)
			must(err)
			if !playing {
				break
			}
			time.Sleep(10 * time.Millisecond)
		}
		time.Sleep(500 * time.Millisecond)
	}

	must(h.Stop()) // Cancel playback, () → error
}

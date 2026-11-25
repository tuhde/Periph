//go:build tinygo

// DRV2605 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, motor.DRV2605I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x5A) → *I2CConnection
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

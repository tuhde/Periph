//go:build tinygo

// DS1881 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Set the volume of two audio channels.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.DS1881I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x2C) → *I2CConnection
	defer conn.Close()

	amp, err := adcdac.NewDS1881Minimal(conn) // Create DS1881 driver, (conn) → (*DS1881Minimal, error)
	must(err)
	must(amp.Init()) // Apply defaults, () → error
	for _, db := range []float32{0, 10, 20, 40} {
		must(amp.SetAttenuation(0, db)) // Set attenuation, (channel 0–1, db dB) → error
		must(amp.SetAttenuation(1, db)) // Set attenuation, (channel 0–1, db dB) → error
		a, err := amp.GetAttenuation(0) // Read attenuation, (channel 0–1) → (float32 dB, error)
		must(err)
		println(db, a)
		time.Sleep(500 * time.Millisecond)
	}
	must(amp.Mute(0)) // Mute channel, (channel 0–1) → error
}

//go:build tinygo

// AD5251 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Sets channel 0 to a few wiper positions, printing the readback and the W–B
// resistance, then sets channel 1 to 20 kΩ.
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
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.AD5251I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x2C) → *I2CConnection
	defer conn.Close()

	pot, err := adcdac.NewAD5251Minimal(conn, adcdac.AD5251DefaultRAB, adcdac.AD5251DefaultRWiper) // Create AD5251 driver, (conn, rAB=50000.0 Ω, rWiper=75.0 Ω) → (*AD5251Minimal, error)
	must(err)
	for _, code := range []int{0, 16, 32, 48, 63} {
		must(pot.SetWiper(0, code)) // Set wiper position, (channel 0–1, code 0–63) → error
		w, err := pot.GetWiper(0)   // Read wiper position, (channel 0–1) → (uint8 0–63, error)
		must(err)
		r, err := pot.GetResistance(0) // Read W–B resistance, (channel 0–1) → (float32 Ω, error)
		must(err)
		println(code, w, r)
		time.Sleep(500 * time.Millisecond)
	}
	must(pot.SetResistance(1, 20000.0)) // Set W–B resistance, (channel 0–1, ohms Ω) → error
}

//go:build tinygo

// AD5243 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Sets channel 0 to a few wiper positions, printing the readback and the W–B
// resistance, then sets channel 1 to 2.2 kΩ.
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
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.AD5243I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x2F) → *I2CConnection
	defer conn.Close()

	pot, err := adcdac.NewAD5243Minimal(conn, adcdac.AD5243DefaultRAB, adcdac.AD5243DefaultRWiper) // Create AD5243 driver, (conn, rAB=10000.0 Ω, rWiper=160.0 Ω) → (*AD5243Minimal, error)
	must(err)
	for _, code := range []int{0, 64, 128, 192, 255} {
		must(pot.SetWiper(0, code)) // Set wiper position, (channel 0–1, code 0–255) → error
		w, err := pot.GetWiper(0)   // Read wiper position, (channel 0–1) → (uint8 0–255, error)
		must(err)
		r, err := pot.GetResistance(0) // Read W–B resistance, (channel 0–1) → (float32 Ω, error)
		must(err)
		println(code, w, r)
		time.Sleep(500 * time.Millisecond)
	}
	must(pot.SetResistance(1, 2200.0)) // Set W–B resistance, (channel 0–1, ohms Ω) → error
}

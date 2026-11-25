//go:build tinygo

// CAT5171 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Steps the wiper through a few positions, printing the readback and the W–B
// resistance, then sets 22 kΩ.
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
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.CAT5171I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x2C) → *I2CConnection
	defer conn.Close()

	pot, err := adcdac.NewCAT5171Minimal(conn, adcdac.CAT5171DefaultRAB, adcdac.CAT5171DefaultRWiper) // Create CAT5171 driver, (conn, rAB=100000.0 Ω, rWiper=50.0 Ω) → (*CAT5171Minimal, error)
	must(err)
	for _, code := range []int{0, 64, 128, 192, 255} {
		must(pot.SetWiper(code)) // Set wiper position, (code 0–255) → error
		w, err := pot.GetWiper() // Read wiper position, () → (uint8 0–255, error)
		must(err)
		r, err := pot.GetResistance() // Read W–B resistance, () → (float32 Ω, error)
		must(err)
		println(code, w, r)
		time.Sleep(500 * time.Millisecond)
	}
	must(pot.SetResistance(22000.0)) // Set W–B resistance, (ohms Ω) → error
}

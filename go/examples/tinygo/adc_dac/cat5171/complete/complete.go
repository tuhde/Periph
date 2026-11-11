//go:build tinygo

// CAT5171 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method of the CAT5171Full API.
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

	pot, err := adcdac.NewCAT5171Full(conn, adcdac.CAT5171DefaultRAB, adcdac.CAT5171DefaultRWiper) // Create CAT5171 Full driver, (conn, rAB=100000.0 Ω, rWiper=50.0 Ω) → (*CAT5171Full, error)
	must(err)
	must(pot.SetWiper(100)) // Set wiper position, (code 0–255) → error
	// writes [instruction, code] with RS = 0, SD = 0
	w, err := pot.GetWiper() // Read wiper position, () → (uint8 0–255, error)
	// bare one-byte read; no instruction byte is sent
	must(err)
	println(w)
	must(pot.SetResistance(50000.0)) // Set W–B resistance, (ohms Ω) → error
	// inverts R = D/256·RAB + RW to the nearest code
	r, err := pot.GetResistance() // Read W–B resistance, () → (float32 Ω, error)
	// evaluates D/256·RAB + RW for the current code
	must(err)
	println(r)
	must(pot.SetRatio(0.25)) // Set divider ratio, (ratio 0.0–1.0) → error
	// code = round(ratio × 256), clamped to 255
	ratio, err := pot.GetRatio() // Read divider ratio, () → (float32, error)
	// code / 256
	must(err)
	println(ratio)
	must(pot.SetResistanceWA(30000.0)) // Set W–A resistance, (ohms Ω) → error
	// rheostat between W and A with B open
	rwa, err := pot.GetResistanceWA() // Read W–A resistance, () → (float32 Ω, error)
	// (256 − D)/256·RAB + RW
	must(err)
	println(rwa)
	n, err := pot.Increment(5) // Step the wiper, (steps) → (uint8 0–255, error)
	// read, add, clamp, write; returns the new code
	must(err)
	println(n)
	must(pot.Midscale()) // Move wiper to midscale, () → error
	// RS = 1 instruction; wiper goes to 0x80
	must(pot.Shutdown()) // Shut down, () → error
	// SD = 1: A opens, wiper shorts to B, WCR kept
	sd := pot.IsShutdown() // Query shutdown state, () → bool
	// driver-tracked; the chip cannot report it
	println(sd)
	time.Sleep(time.Second)
	must(pot.Resume()) // Leave shutdown, () → error
	// rewrites the kept code with SD = 0
	pot.SetRAB(50000.0, 120.0) // Change resistance model, (rAB Ω, rWiper Ω) → ()
	// affects conversions only; no I²C traffic
	must(pot.Reset()) // Midscale reset, () → error
	// RS = 1, SD = 0; wiper returns to 0x80
}

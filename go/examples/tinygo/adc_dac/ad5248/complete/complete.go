//go:build tinygo

// AD5248 complete — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}
	conn := connection.NewI2CConnection(i2c, 0x2C, nil, nil) // Create I2C connection, (i2c, addr=0x2C) → (*I2CConnection)

	pot := adcdac.NewAD5248Full(conn, 10000, 160) // Create AD5248 driver, (connection, rAB=10000 Ω, rWiper=160 Ω) → *AD5248Full

	// --- Wiper position ---
	if err := pot.SetWiper(0, 100); err != nil { // Set wiper, (channel 0-1, code 0-255) → error
		panic(err)
	}
	// writes [channel << 7, code]; code clamped to 0-255
	code, err := pot.GetWiper(0) // Read wiper, (channel 0-1) → (uint8 0-255, error)
	if err != nil {
		panic(err)
	}
	// sends the instruction byte alone, then reads one byte
	fmt.Println(code)
	if err := pot.SetBoth(10, 20); err != nil { // Set both wipers, (code1 0-255, code2 0-255) → error
		panic(err)
	}
	// two transactions, one per channel

	// --- Resistance ---
	if err := pot.SetResistance(1, 2200); err != nil { // Set W-B resistance, (channel 0-1, ohms) → error
		panic(err)
	}
	// nearest code of R = code/256*RAB + 2*RW, clamped
	ohms, err := pot.GetResistance(1) // Read W-B resistance, (channel 0-1) → (float64 Ω, error)
	if err != nil {
		panic(err)
	}
	// code/256*RAB + 2*RW
	fmt.Println(ohms)

	// --- Ratio and increment ---
	if err := pot.SetRatio(0, 0.5); err != nil { // Set wiper ratio, (channel 0-1, ratio 0.0-1.0) → error
		panic(err)
	}
	// code = round(ratio*256) clamped to 255; needs terminal A wired
	ratio, err := pot.GetRatio(0) // Read wiper ratio, (channel 0-1) → (float64 0.0-1.0, error)
	if err != nil {
		panic(err)
	}
	// code / 256
	fmt.Println(ratio)
	next, err := pot.Increment(0, 5) // Step wiper, (channel 0-1, steps) → (uint8 0-255, error)
	if err != nil {
		panic(err)
	}
	// read-modify-write, clamped to 0-255; negative steps decrement
	fmt.Println(next)

	// --- Shutdown ---
	if err := pot.Shutdown(1); err != nil { // Shut channel down, (channel 0-1) → error
		panic(err)
	}
	// opens terminal A, shorts wiper to B, RDAC kept
	down, err := pot.IsShutdown(1) // Query driver-tracked state, (channel 0-1) → (bool, error)
	if err != nil {
		panic(err)
	}
	// the chip cannot be read back
	fmt.Println(down)
	if err := pot.Resume(1); err != nil { // Leave shutdown, (channel 0-1) → error
		panic(err)
	}
	// rewrites the RDAC with SD = 0

	// --- Model and reset ---
	pot.SetRab(10000, 160) // Set resistance model, (rAB Ω, rWiper Ω) → void
	// no I²C traffic; affects conversions only
	if err := pot.Reset(); err != nil { // Reset to power-up preset, () → error
		panic(err)
	}
	// writes midscale 0x80 to both channels with SD = 0
}

//go:build tinygo

// AD5251 demo example — TinyGo / Raspberry Pi Pico W.
//
// Voltage-divider level setter: steps channel 0 as a divider (A at VDD, B at
// GND) through several ratios and prints the expected output voltage at
// VDD = 3.3 V; sets channel 1 as a 20 kΩ rheostat; steps the wiper with the quick
// commands, stores and restores it, uses the user EEMEM, prints the stored
// RAB tolerance and resets from EEMEM.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

const vdd float32 = 3.3

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.AD5251I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x2C) → *I2CConnection
	defer conn.Close()

	pot, err := adcdac.NewAD5251Full(conn, adcdac.AD5251DefaultRAB, adcdac.AD5251DefaultRWiper) // Create AD5251 Full driver, (conn, rAB=50000.0 Ω, rWiper=75.0 Ω) → (*AD5251Full, error)
	must(err)

	// --- Step channel 0 through divider ratios ---
	// With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
	// round(ratio × 64), so 0.999 lands on the top code 63.
	for _, ratio := range []float32{0.0, 0.25, 0.5, 0.75, 0.999} {
		must(pot.SetRatio(0, ratio)) // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → error
		code, err := pot.GetWiper(0) // Read wiper position, (channel 0–1) → (uint8 0–63, error)
		must(err)
		actual, err := pot.GetRatio(0) // Read divider ratio, (channel 0–1) → (float32, error)
		must(err)
		println("ratio", ratio, "-> code", code, "->", actual*vdd, "V")
		time.Sleep(500 * time.Millisecond)
	}

	// --- Use channel 1 as a 20 kΩ rheostat ---
	// The 64 steps quantise the resistance (~781 Ω per code on a 50 kΩ part), so
	// the readback differs slightly from the request.
	must(pot.SetResistance(1, 20000.0)) // Set W–B resistance, (channel 0–1, ohms Ω) → error
	r, err := pot.GetResistance(1)      // Read W–B resistance, (channel 0–1) → (float32 Ω, error)
	must(err)
	println("rheostat", r, "ohm, error", r-20000.0, "ohm")

	// --- Step and scale the wiper with the quick commands ---
	// Increment/Decrement move one code, Double/Halve change the level by 6 dB.
	must(pot.SetWiper(0, 16)) // Set wiper position, (channel 0–1, code 0–63) → error
	for _, step := range []struct {
		name string
		fn   func(uint8) error
	}{{"increment", pot.Increment}, {"decrement", pot.Decrement}, {"double", pot.Double}, {"halve", pot.Halve}} {
		must(step.fn(0)) // Quick command, (channel 0–1 or AD5251Both) → error
		w, err := pot.GetWiper(0)
		must(err)
		println(step.name, "->", w)
	}

	// --- Make the settings persistent ---
	// Store() writes the wipers to EEMEM (≈ 26 ms, ACK polling); after a change,
	// Restore() brings the stored values back.
	must(pot.Store(adcdac.AD5251Both))   // Store wipers in EEMEM, (channel 0–1 or AD5251Both) → error
	must(pot.SetBoth(1, 2))              // Write both wipers, (code0 0–63, code1 0–63) → error
	must(pot.Restore(adcdac.AD5251Both)) // Restore wipers from EEMEM, (channel 0–1 or AD5251Both) → error
	w0, err := pot.GetWiper(0)
	must(err)
	w1, err := pot.GetWiper(1)
	must(err)
	println("restored:", w0, w1)

	// --- User EEMEM bytes and the factory tolerance ---
	must(pot.WriteEEMEM(4, 0x40)) // Write user EEMEM byte, (index 4–15, value 0–63) → error
	e, err := pot.ReadEEMEM(4)    // Read user EEMEM byte, (index 4–15) → (uint8, error)
	must(err)
	println("eemem 4:", e)
	tol, err := pot.ReadTolerance(0) // Read stored RAB tolerance, (channel 0–1) → (float32 %, error)
	must(err)
	rab, err := pot.RABActual(0) // Actual RAB of a channel, (channel 0–1) → (float32 Ω, error)
	must(err)
	println("tolerance", tol, "% RAB", rab, "ohm")

	// --- Restore the stored state ---
	// Reset() restores both wipers from EEMEM and waits for the write cycle.
	must(pot.Reset()) // Reset from EEMEM, () → error
}

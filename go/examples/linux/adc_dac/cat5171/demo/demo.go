//go:build linux && !tinygo

// CAT5171 demo example — Linux host.
//
// Programmable level trimmer: sweeps the wiper in steps of 16 printing the W–B
// resistance, sets a 22 kΩ rheostat and reports the quantisation error,
// demonstrates shutdown/resume, midscale and reset, and steps the wiper as a
// voltage divider (A at VDD, B at GND) printing the expected output voltage
// at VDD = 3.3 V.
package main

import (
	"fmt"
	"os"
	"strconv"
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

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, adcdac.CAT5171I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x2C) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	pot, err := adcdac.NewCAT5171Full(conn, adcdac.CAT5171DefaultRAB, adcdac.CAT5171DefaultRWiper) // Create CAT5171 Full driver, (conn, rAB=100000.0 Ω, rWiper=50.0 Ω) → (*CAT5171Full, error)
	must(err)

	// --- Sweep the wiper across the full range ---
	// Zero scale is not 0 Ω (it is the wiper resistance) and code 255 sits one
	// LSB below the RAB end.
	for code := 0; code < 256; code += 16 {
		must(pot.SetWiper(code))      // Set wiper position, (code 0–255) → error
		r, err := pot.GetResistance() // Read W–B resistance, () → (float32 Ω, error)
		must(err)
		fmt.Println("code", code, "->", r, "Ω")
		time.Sleep(200 * time.Millisecond)
	}

	// --- Set a 22 kΩ rheostat ---
	// The 256 steps quantise the resistance (~390 Ω per code on a 100 kΩ part),
	// so the readback differs slightly from the request.
	must(pot.SetResistance(22000.0)) // Set W–B resistance, (ohms Ω) → error
	rheo, err := pot.GetResistance() // Read W–B resistance, () → (float32 Ω, error)
	must(err)
	fmt.Println("rheostat", rheo, "Ω, error", rheo-22000.0, "Ω")

	// --- Shutdown, resume, midscale and reset ---
	// Shutdown opens A and shorts the wiper to B while the WCR keeps its value,
	// so resume restores the setting; midscale and reset both return to 0x80.
	kept, err := pot.GetWiper() // Read wiper position, () → (uint8 0–255, error)
	must(err)
	must(pot.Shutdown())   // Shut down, () → error
	sd := pot.IsShutdown() // Query shutdown state, () → bool
	now, err := pot.GetWiper()
	must(err)
	fmt.Println("shutdown:", sd, "kept", now == kept)
	time.Sleep(time.Second)
	must(pot.Resume())   // Leave shutdown, () → error
	must(pot.Midscale()) // Move wiper to midscale, () → error
	must(pot.Reset())    // Midscale reset, () → error

	// --- Use the wiper as a voltage divider ---
	// With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
	// round(ratio × 256), so 0.999 lands on the top code 255.
	for _, ratio := range []float32{0.0, 0.25, 0.5, 0.75, 0.999} {
		must(pot.SetRatio(ratio))     // Set divider ratio, (ratio 0.0–1.0) → error
		actual, err := pot.GetRatio() // Read divider ratio, () → (float32, error)
		must(err)
		fmt.Println("ratio", ratio, "->", actual, "->", actual*vdd, "V")
		time.Sleep(500 * time.Millisecond)
	}
}

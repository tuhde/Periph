//go:build linux && !tinygo

// AD5243 demo example — Linux host.
//
// Voltage-divider level setter: steps channel 0 as a divider (A at VDD, B at
// GND) through several ratios and prints the expected output voltage at
// VDD = 3.3 V; sets channel 1 as a 2.2 kΩ rheostat; demonstrates shutdown,
// resume and reset.
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

	conn, err := connection.NewI2CConnection(bus, adcdac.AD5243I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x2F) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	pot, err := adcdac.NewAD5243Full(conn, adcdac.AD5243DefaultRAB, adcdac.AD5243DefaultRWiper) // Create AD5243 Full driver, (conn, rAB=10000.0 Ω, rWiper=160.0 Ω) → (*AD5243Full, error)
	must(err)

	// --- Step channel 0 through divider ratios ---
	// With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
	// round(ratio × 256), so 0.999 lands on the top code 255.
	for _, ratio := range []float32{0.0, 0.25, 0.5, 0.75, 0.999} {
		must(pot.SetRatio(0, ratio)) // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → error
		code, err := pot.GetWiper(0) // Read wiper position, (channel 0–1) → (uint8 0–255, error)
		must(err)
		actual, err := pot.GetRatio(0) // Read divider ratio, (channel 0–1) → (float32, error)
		must(err)
		fmt.Println("ratio", ratio, "-> code", code, "->", actual*vdd, "V")
		time.Sleep(500 * time.Millisecond)
	}

	// --- Use channel 1 as a 2.2 kΩ rheostat ---
	// The 256 steps quantise the resistance (~39 Ω per code on a 10 kΩ part), so
	// the readback differs slightly from the request.
	must(pot.SetResistance(1, 2200.0)) // Set W–B resistance, (channel 0–1, ohms Ω) → error
	r, err := pot.GetResistance(1)     // Read W–B resistance, (channel 0–1) → (float32 Ω, error)
	must(err)
	fmt.Println("rheostat", r, "ohm, error", r-2200.0, "ohm")

	// --- Shut channel 1 down and bring it back ---
	// Shutdown opens A and shorts the wiper to B (about 0 Ω) while the RDAC keeps
	// its value, so resume restores the 2.2 kΩ setting.
	must(pot.Shutdown(1))        // Shut channel down, (channel 0–1) → error
	sd, err := pot.IsShutdown(1) // Query shutdown state, (channel 0–1) → (bool, error)
	must(err)
	fmt.Println("shutdown:", sd)
	time.Sleep(time.Second)
	must(pot.Resume(1)) // Leave shutdown, (channel 0–1) → error

	// --- Restore the power-up state ---
	// The chip has no reset pin, so Reset() rewrites midscale to both channels.
	must(pot.Reset()) // Restore power-up preset, () → error
}

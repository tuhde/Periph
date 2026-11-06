//go:build linux && !tinygo

// AD5243 complete example — Linux host.
//
// Exercises every method of the AD5243Full API.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

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
	must(pot.SetWiper(0, 100)) // Set wiper position, (channel 0–1, code 0–255) → error
	// writes [instruction, code]; the instruction byte selects the channel
	w, err := pot.GetWiper(0) // Read wiper position, (channel 0–1) → (uint8 0–255, error)
	// sends the instruction alone, then reads one byte
	must(err)
	fmt.Println(w)
	must(pot.SetResistance(0, 5000.0)) // Set W–B resistance, (channel 0–1, ohms Ω) → error
	// inverts R = D/256·RAB + 2·RW to the nearest code
	r, err := pot.GetResistance(0) // Read W–B resistance, (channel 0–1) → (float32 Ω, error)
	// evaluates D/256·RAB + 2·RW for the current code
	must(err)
	fmt.Println(r)
	must(pot.SetBoth(32, 224)) // Write both wipers, (code1 0–255, code2 0–255) → error
	// two transactions, channel 0 then channel 1
	must(pot.SetRatio(0, 0.25)) // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → error
	// code = round(ratio × 256), clamped to 255
	ratio, err := pot.GetRatio(0) // Read divider ratio, (channel 0–1) → (float32, error)
	// code / 256
	must(err)
	fmt.Println(ratio)
	must(pot.SetResistanceWA(1, 3000.0)) // Set W–A resistance, (channel 0–1, ohms Ω) → error
	// rheostat between W and A with B open
	rwa, err := pot.GetResistanceWA(1) // Read W–A resistance, (channel 0–1) → (float32 Ω, error)
	// (256 − D)/256·RAB + 2·RW
	must(err)
	fmt.Println(rwa)
	n, err := pot.Increment(0, 5) // Step the wiper, (channel 0–1, steps) → (uint8 0–255, error)
	// read, add, clamp, write; returns the new code
	must(err)
	fmt.Println(n)
	must(pot.Shutdown(1)) // Shut channel down, (channel 0–1) → error
	// SD = 1: A opens, wiper shorts to B, RDAC kept
	sd, err := pot.IsShutdown(1) // Query shutdown state, (channel 0–1) → (bool, error)
	// driver-tracked; the chip cannot report it
	must(err)
	fmt.Println(sd)
	time.Sleep(time.Second)
	must(pot.Resume(1)) // Leave shutdown, (channel 0–1) → error
	// rewrites the kept code with SD = 0
	pot.SetRAB(50000.0, 120.0) // Change resistance model, (rAB Ω, rWiper Ω) → ()
	// affects conversions only; no I²C traffic
	must(pot.Reset()) // Restore power-up preset, () → error
	// writes 0x80 to both channels with SD = 0
}

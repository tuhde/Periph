//go:build tinygo

// LTC4151 demo example — TinyGo / Raspberry Pi Pico W.
//
// High-side supply monitor scenario from the spec. Uses machine.I2C1 with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
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

	conn := connection.NewI2CConnection(i2c, 0x6A, nil, nil) // Create I2C connection, (i2c, addr=0x6A) → (*I2CConnection)
	chip, err := power.NewLTC4151Full(conn, 0.02)            // Create LTC4151 driver, (connection, rSense=0.02 Ω) → (*LTC4151Full, error)
	if err != nil {
		panic(err)
	}

	must := func(err error) {
		if err != nil {
			panic(err)
		}
	}

	// --- High-side supply monitor ---
	// Continuous scan: print every channel once per second for 20 s.
	for n := 0; n < 20; n++ {
		r, err := chip.ReadAll() // Consistent read of all channels, () → (LTC4151Readings, error)
		must(err)
		fmt.Printf("%.2f V  %.2f mV  %.4f A  %.3f W  adin %.3f V\n", r.Vin, r.SenseV*1000, r.Current, r.Power, r.Adin)
		time.Sleep(time.Second)
	}

	// --- Compare snapshot conversion times ---
	// SENSE needs about 67 ms, VIN and ADIN about 33 ms.
	for name, ch := range map[string]uint8{"SENSE": power.LTC4151Sense, "VIN": power.LTC4151Vin, "ADIN": power.LTC4151Adin} {
		t0 := time.Now()
		val, err := chip.Snapshot(ch) // One snapshot conversion, (channel 0–2) → (float32 V, error)
		must(err)
		fmt.Printf("%s snapshot %.4f V in %d ms\n", name, val, time.Since(t0).Milliseconds())
	}
	m, err := chip.IsSnapshotMode() // Snapshot mode active, () → (bool, error)
	must(err)
	fmt.Println("snapshot mode afterwards", m)

	// --- Toggle the stuck-bus timer and page read/write ---
	// The CONTROL register shows both bits cleared, then restored.
	must(chip.SetStuckBusTimer(false)) // Stuck-bus reset timer, (enabled) → error
	must(chip.SetPageRW(false))        // I²C page read/write, (enabled) → error
	ctrl, err := chip.ReadControl()    // Read CONTROL, () → (uint8, error)
	must(err)
	fmt.Printf("CONTROL (timer off, page off) 0x%02X\n", ctrl)
	must(chip.SetStuckBusTimer(true))
	must(chip.SetPageRW(true))
	ctrl, err = chip.ReadControl()
	must(err)
	fmt.Printf("CONTROL (restored) 0x%02X\n", ctrl)

	// --- NTC temperature from ADIN ---
	// A made-up ADIN / VIN code pair run through the datasheet example formula.
	fmt.Printf("temperature %.1f C\n", power.LTC4151TemperatureFromAdin(900, 1200)) // NTC example circuit, (adinCode, vinCode) → float32 °C

	// --- Power tally ---
	// Sample the power 25 times at 200 ms (5 s) and print min, max and average.
	var pmin, pmax, psum float32 = 1e9, 0, 0
	for n := 0; n < 25; n++ {
		p, err := chip.Power() // Read load power, () → (float32 W, error)
		must(err)
		if p < pmin {
			pmin = p
		}
		if p > pmax {
			pmax = p
		}
		psum += p
		time.Sleep(200 * time.Millisecond)
	}
	fmt.Printf("power min %.3f W, max %.3f W, avg %.3f W\n", pmin, pmax, psum/25)
}

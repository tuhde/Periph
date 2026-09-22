//go:build tinygo

// HX710B demo example — TinyGo / Raspberry Pi Pico W.
//
// Battery-powered load cell on a Pico W. Tares the scale at startup
// (averaging 10 readings), then loops reading the weight every 500
// ms and printing the result in grams — only emits a "-> N g" line
// when the value changes by more than 1 g. Captures one
// supply-difference reading at startup as a known-good battery
// baseline, then every 20th iteration (~10 s) takes a fresh
// reading and prints a LOW BATTERY warning when it has drifted from
// the baseline. The supply-difference channel is uncalibrated; the
// reading is useful only for relative drift tracking, not as an
// absolute voltage. scaleFactor is calibrated for a 100 g reference
// weight; substitute your own.
package main

import (
	"fmt"
	"math"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	scaleFactor  = 420.0
	lowBattDelta = 50000.0
)

func main() {
	conn := connection.NewHX711Connection(machine.GP2, machine.GP3, nil) // Create HX711 transport connection, (dout=GP2, pd_sck=GP3) → (*HX711Connection)
	hx, err := adcdac.NewHX710BFull(conn)                                // Create HX710B driver, (connection) → (*HX710BFull, error)
	if err != nil {
		panic(err)
	}

	// --- Tare the scale at startup ---
	fmt.Println("Taring...")
	if err := hx.Tare(10); err != nil { // Capture zero offset, (times=10) → error
		fmt.Printf("tare: %v\n", err)
		return
	}
	hx.SetScale(scaleFactor) // Set scale factor, (factor=scaleFactor) → ()
	fmt.Println("Ready. Place an item on the scale.")

	// --- Capture the supply-difference baseline at full charge ---
	baselineSuppDiff, err := hx.ReadSupplyDiffRaw() // Read raw DVDD−AVDD supply-difference code, () → (int32, error)
	if err != nil {
		fmt.Printf("read_supply_diff_raw: %v\n", err)
		return
	}
	fmt.Printf("baseline supply_diff=%d\n", baselineSuppDiff)

	// --- Continuous read loop with change-only printing and periodic supply-diff check ---
	var lastWeight float32
	first := true
	iteration := 0
	for {
		w, err := hx.ReadWeight(3) // Read calibrated weight, (times=3) → (float32, error)
		if err != nil {
			fmt.Printf("read_weight: %v\n", err)
			time.Sleep(500 * time.Millisecond)
			continue
		}
		if first || (w-lastWeight > 1.0) || (lastWeight-w > 1.0) {
			fmt.Printf("-> %.1f g\n", w)
			lastWeight = w
			first = false
		}

		if iteration%20 == 0 {
			suppDiff, err := hx.ReadSupplyDiffRaw() // Read raw DVDD−AVDD supply-difference code, () → (int32, error)
			if err == nil {
				drift := float64(suppDiff - baselineSuppDiff)
				if math.Abs(drift) > lowBattDelta {
					fmt.Printf("LOW BATTERY (supply_diff=%d, drift=%.0f)\n", suppDiff, drift)
				}
			}
		}
		iteration++

		time.Sleep(500 * time.Millisecond)
	}
}

//go:build linux && !tinygo

// HX710B demo example — Linux host.
//
// Battery-powered load cell: tares the scale at startup (averaging
// 10 readings), then loops continuously reading the weight every
// 500 ms and printing a "-> N g" update whenever the reading
// changes by more than 1 gram. Captures one supply-difference
// reading as a "known-good battery" baseline at startup, then every
// 20th iteration (~10 s) takes a fresh reading and emits a
// LOW BATTERY warning when it drifts from the baseline by more than
// the configured threshold. The supply-difference channel is
// uncalibrated; the reading is useful only for relative drift
// tracking against a known-good baseline, not as an absolute
// voltage. The scaleFactor constant is calibrated for a 100 g
// reference weight; substitute your own.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	scaleFactor    = 420.0
	lowBattDelta   = 50000
)

func main() {
	dout, err := strconv.Atoi(envOr("HX710B_DOUT", "2"))
	if err != nil {
		panic(err)
	}
	pdSck, err := strconv.Atoi(envOr("HX710B_PD_SCK", "3"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewHX711Connection(dout, pdSck, nil) // Create HX711 transport connection, (dout=2, pd_sck=3) → (*HX711Connection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	hx, err := adcdac.NewHX710BFull(conn) // Create HX710B driver, (connection) → (*HX710BFull, error)
	if err != nil {
		panic(err)
	}

	// --- Tare the scale at startup ---
	// Averages 10 readings, stores as the zero offset. After this,
	// ReadWeight returns (raw - offset) / scale_factor.
	fmt.Println("Taring...")
	if err := hx.Tare(10); err != nil { // Capture zero offset, (times=10) → error
		fmt.Fprintln(os.Stderr, "tare:", err)
		return
	}
	hx.SetScale(scaleFactor) // Set scale factor, (factor=scaleFactor) → ()
	fmt.Println("Ready. Place an item on the scale.")

	// --- Capture the supply-difference baseline at full charge ---
	// Uncalibrated ADC code; capture one reading at startup as a
	// known-good baseline, then compare later readings against it
	// to detect battery discharge.
	baselineSuppDiff, err := hx.ReadSupplyDiffRaw() // Read raw DVDD−AVDD supply-difference code, () → (int32, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_supply_diff_raw:", err)
		return
	}
	fmt.Printf("baseline supply_diff=%d\n", baselineSuppDiff)

	// --- Continuous read loop with change-only printing and periodic supply-diff check ---
	// Samples every 500 ms but only emits a weight line when the
	// value shifts by more than 1 g. Every 20th iteration (~10 s),
	// samples the supply-difference channel and emits a LOW BATTERY
	// warning if it has drifted from the baseline by more than the
	// configured threshold — the datasheet's stated purpose for this
	// channel in battery-powered weigh-scale applications.
	var lastWeight float32
	first := true
	iteration := 0
	for {
		w, err := hx.ReadWeight(3) // Read calibrated weight, (times=3) → (float32, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "read_weight:", err)
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

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build tinygo

// HX710A hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W with HX710A DOUT on GP2 and PD_SCK on GP3.
// Runs the HX710A check sequence: smoke test for both Minimal and
// Full, rate selection, averaging, tare, scale, weight, and
// temperature. Power-down/power-up is exercised only in the
// Complete example, not here. Prints PASS/FAIL per check and ends
// with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	conn := connection.NewHX711Connection(machine.GP2, machine.GP3, nil) // Create HX711 transport connection, (dout=GP2, pd_sck=GP3) → (*HX711Connection)

	// --- HX710AMinimal ---
	{
		hx, err := adcdac.NewHX710AMinimal(conn) // Create HX710A driver, (connection) → (*HX710AMinimal, error)
		check("minimal_construct", err == nil && hx != nil)

		ready, err := hx.IsReady() // Check if data is ready, () → (bool, error)
		check("minimal_is_ready_ok", err == nil)
		_ = ready

		raw, err := hx.ReadRaw() // Read 24-bit differential-input value, () → (int32, error)
		check("minimal_read_raw_ok", err == nil)
		if err == nil {
			check("minimal_read_raw_in_range", raw >= -8388608 && raw <= 8388607)
		}
	}

	// --- HX710AFull ---
	{
		hx, err := adcdac.NewHX710AFull(conn) // Create HX710A driver, (connection) → (*HX710AFull, error)
		check("full_construct", err == nil && hx != nil)

		ready, err := hx.IsReady() // Check if data is ready, () → (bool, error)
		check("full_is_ready_ok", err == nil)
		_ = ready

		raw, err := hx.ReadRaw() // Read 24-bit differential-input value, () → (int32, error)
		check("full_read_raw_ok", err == nil)
		if err == nil {
			check("full_read_raw_in_range", raw >= -8388608 && raw <= 8388607)
		}

		check("set_rate_40_ok", hx.SetRate(adcdac.HX710ARate40SPS) == nil) // Select rate, (rate=40) → error
		check("set_rate_10_ok", hx.SetRate(adcdac.HX710ARate10SPS) == nil)
		check("set_rate_invalid_rejected", hx.SetRate(20) != nil)
		_ = hx.SetRate(adcdac.HX710ARate10SPS)

		avg, err := hx.ReadAverage(3) // Read averaged value, (times=3) → (int32, error)
		check("full_read_average_ok", err == nil)
		if err == nil {
			check("full_read_average_in_range", avg >= -8388608 && avg <= 8388607)
		}

		check("tare_ok", hx.Tare(3) == nil) // Capture zero offset, (times=3) → error
		offset := hx.GetOffset()            // Get tare offset, () → int32
		check("get_offset_in_range", offset >= -8388608 && offset <= 8388607)

		hx.SetScale(420.0) // Set scale factor, (factor=420.0) → ()
		check("get_scale_set", hx.GetScale() == 420.0)

		weight, err := hx.ReadWeight(3) // Read calibrated weight, (times=3) → (float32, error)
		check("read_weight_ok", err == nil)
		if err == nil {
			check("read_weight_finite", weight == weight)
		}

		tempRaw, err := hx.ReadTemperatureRaw() // Read raw on-chip temperature code, () → (int32, error)
		check("read_temperature_raw_ok", err == nil)
		if err == nil {
			check("read_temperature_raw_in_range", tempRaw >= -8388608 && tempRaw <= 8388607)
		}
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

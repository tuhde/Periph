//go:build tinygo

// HX711 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W with HX711 DOUT on GP2 and PD_SCK on GP3.
// Runs the HX711 check sequence: smoke test for both Minimal and
// Full, gain selection, averaging, tare, scale, and weight.
// Power-down/power-up is exercised only in the Complete example, not
// here. Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
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

	conn := connection.NewHX711Connection(machine.GP2, machine.GP3, nil) // Create HX711 connection, (dout=GP2, pd_sck=GP3) → (*HX711Connection)

	// --- HX711Minimal ---
	{
		hx, err := adcdac.NewHX711Minimal(conn) // Create HX711 driver, (connection) → (*HX711Minimal, error)
		check("minimal_construct", err == nil && hx != nil)

		ready, err := hx.IsReady() // Check if data is ready, () → (bool, error)
		check("minimal_is_ready_ok", err == nil)
		_ = ready

		raw, err := hx.ReadRaw() // Read 24-bit ADC value, () → (int32, error)
		check("minimal_read_raw_ok", err == nil)
		if err == nil {
			check("minimal_read_raw_in_range", raw >= -8388608 && raw <= 8388607)
		}
	}

	// --- HX711Full ---
	{
		hx, err := adcdac.NewHX711Full(conn) // Create HX711 driver, (connection) → (*HX711Full, error)
		check("full_construct", err == nil && hx != nil)

		ready, err := hx.IsReady() // Check if data is ready, () → (bool, error)
		check("full_is_ready_ok", err == nil)
		_ = ready

		raw, err := hx.ReadRaw() // Read 24-bit ADC value, () → (int32, error)
		check("full_read_raw_ok", err == nil)
		if err == nil {
			check("full_read_raw_in_range", raw >= -8388608 && raw <= 8388607)
		}

		check("set_gain_128_ok", hx.SetGain(adcdac.HX711Gain128) == nil) // Select gain, (gain=128) → error
		check("set_gain_64_ok", hx.SetGain(adcdac.HX711Gain64) == nil)
		check("set_gain_32_ok", hx.SetGain(adcdac.HX711Gain32) == nil)
		check("set_gain_invalid_rejected", hx.SetGain(99) != nil)
		_ = hx.SetGain(adcdac.HX711Gain128)

		avg, err := hx.ReadAverage(3) // Read averaged value, (times=3) → (int32, error)
		check("full_read_average_ok", err == nil)
		if err == nil {
			check("full_read_average_in_range", avg >= -8388608 && avg <= 8388607)
		}

		check("tare_ok", hx.Tare(3) == nil) // Capture zero offset, (times=3) → error
		offset := hx.GetOffset()             // Get tare offset, () → int32
		check("get_offset_in_range", offset >= -8388608 && offset <= 8388607)

		hx.SetScale(420.0)                  // Set scale factor, (factor=420.0) → ()
		check("get_scale_set", hx.GetScale() == 420.0)

		weight, err := hx.ReadWeight(3) // Read calibrated weight, (times=3) → (float32, error)
		check("read_weight_ok", err == nil)
		if err == nil {
			check("read_weight_finite", weight == weight)
		}

	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

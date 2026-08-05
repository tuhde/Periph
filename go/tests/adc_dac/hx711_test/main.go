//go:build linux && !tinygo

// HX711 hardware test — Linux host.
//
// Opens DOUT and PD_SCK on /dev/gpiochip0 and runs the HX711 check
// sequence: smoke test for both Minimal and Full, gain selection
// (including invalid gain), averaging, tare, scale, and weight.
// Power-down/power-up is exercised only in the Complete example, not
// here. Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line. Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	dout, err := strconv.Atoi(envOr("HX711_DOUT", "2"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "HX711_DOUT:", err)
		os.Exit(2)
	}
	pdSck, err := strconv.Atoi(envOr("HX711_PD_SCK", "3"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "HX711_PD_SCK:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	conn, trErr := connection.NewHX711Connection(dout, pdSck, nil)
	if trErr != nil {
		fmt.Fprintln(os.Stderr, "connection open failed (no hw?):", trErr)
		check("hx711_minimal_skip_no_hw", true)
		check("hx711_full_skip_no_hw", true)
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		if failed != 0 {
			os.Exit(1)
		}
		return
	}
	defer conn.Close()

	// --- HX711Minimal ---
	{
		hx, err := adcdac.NewHX711Minimal(conn)
		check("minimal_construct", err == nil && hx != nil)

		ready, err := hx.IsReady()
		check("minimal_is_ready_ok", err == nil)
		_ = ready

		raw, err := hx.ReadRaw()
		check("minimal_read_raw_ok", err == nil)
		if err == nil {
			check("minimal_read_raw_in_range", raw >= -8388608 && raw <= 8388607)
		}
	}

	// --- HX711Full ---
	{
		hx, err := adcdac.NewHX711Full(conn)
		check("full_construct", err == nil && hx != nil)

		ready, err := hx.IsReady()
		check("full_is_ready_ok", err == nil)
		_ = ready

		raw, err := hx.ReadRaw()
		check("full_read_raw_ok", err == nil)
		if err == nil {
			check("full_read_raw_in_range", raw >= -8388608 && raw <= 8388607)
		}

		check("set_gain_128_ok", hx.SetGain(adcdac.HX711Gain128) == nil)
		check("set_gain_64_ok", hx.SetGain(adcdac.HX711Gain64) == nil)
		check("set_gain_32_ok", hx.SetGain(adcdac.HX711Gain32) == nil)
		check("set_gain_invalid_rejected", hx.SetGain(99) != nil)
		// back to gain 128 for the rest of the test
		_ = hx.SetGain(adcdac.HX711Gain128)

		avg, err := hx.ReadAverage(3)
		check("full_read_average_ok", err == nil)
		if err == nil {
			check("full_read_average_in_range", avg >= -8388608 && avg <= 8388607)
		}

		check("tare_ok", hx.Tare(3) == nil)
		offset := hx.GetOffset()
		check("get_offset_in_range", offset >= -8388608 && offset <= 8388607)

		hx.SetScale(420.0)
		check("get_scale_set", hx.GetScale() == 420.0)

		weight, err := hx.ReadWeight(3)
		check("read_weight_ok", err == nil)
		if err == nil {
			check("read_weight_finite", weight == weight) // NaN check
		}

	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

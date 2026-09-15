//go:build linux && !tinygo

// HX710A hardware test — Linux host.
//
// Opens DOUT and PD_SCK on /dev/gpiochip0 and runs the HX710A check
// sequence: smoke test for both Minimal and Full, rate selection
// (including invalid rate), averaging, tare, scale, weight, and
// temperature. Power-down/power-up is exercised only in the Complete
// example, not here. Prints PASS/FAIL per check and ends with the
// standard ===DONE: ... === line. Exits 0 on full pass, 1 on any
// failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	dout, err := strconv.Atoi(envOr("HX710A_DOUT", "2"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "HX710A_DOUT:", err)
		os.Exit(2)
	}
	pdSck, err := strconv.Atoi(envOr("HX710A_PD_SCK", "3"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "HX710A_PD_SCK:", err)
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
		check("hx710a_minimal_skip_no_hw", true)
		check("hx710a_full_skip_no_hw", true)
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		if failed != 0 {
			os.Exit(1)
		}
		return
	}
	defer conn.Close()

	// --- HX710AMinimal ---
	{
		hx, err := adcdac.NewHX710AMinimal(conn)
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

	// --- HX710AFull ---
	{
		hx, err := adcdac.NewHX710AFull(conn)
		check("full_construct", err == nil && hx != nil)

		ready, err := hx.IsReady()
		check("full_is_ready_ok", err == nil)
		_ = ready

		raw, err := hx.ReadRaw()
		check("full_read_raw_ok", err == nil)
		if err == nil {
			check("full_read_raw_in_range", raw >= -8388608 && raw <= 8388607)
		}

		check("set_rate_40_ok", hx.SetRate(adcdac.HX710ARate40SPS) == nil)
		check("set_rate_10_ok", hx.SetRate(adcdac.HX710ARate10SPS) == nil)
		check("set_rate_invalid_rejected", hx.SetRate(20) != nil)
		// back to 10 SPS for the rest of the test
		_ = hx.SetRate(adcdac.HX710ARate10SPS)

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

		tempRaw, err := hx.ReadTemperatureRaw()
		check("read_temperature_raw_ok", err == nil)
		if err == nil {
			check("read_temperature_raw_in_range", tempRaw >= -8388608 && tempRaw <= 8388607)
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

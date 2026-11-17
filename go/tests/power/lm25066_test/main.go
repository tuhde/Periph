//go:build linux && !tinygo

// LM25066 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the LM25066 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip := power.NewLM25066Full(conn, 1.0, false)

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

	fp := func(v float64) *float64 { return &v }

	check("model_is_lm25066", func() bool { v, err := chip.Model(); return err == nil && v == "LM25066" }())
	check("manufacturer_id", func() bool { v, err := chip.ManufacturerID(); return err == nil && v == "NSC" }())
	check("vin_range", func() bool { v, err := chip.VoltageIn(); return err == nil && v >= 0 && v <= 24 }())
	check("vout_range", func() bool { v, err := chip.VoltageOut(); return err == nil && v >= 0 && v <= 24 }())
	check("current_readable", func() bool { v, err := chip.Current(); return err == nil && v > -1 }())
	check("power_readable", func() bool { v, err := chip.Power(); return err == nil && v > -5 }())
	check("temperature_plausible", func() bool { v, err := chip.Temperature(); return err == nil && v > -40 && v < 150 }())
	check("vaux_range", func() bool { v, err := chip.VoltageAux(); return err == nil && v >= 0 && v <= 1.2 }())
	check("block_readable", func() bool { _, err := chip.ReadBlock(); return err == nil }())
	check("avg_block_readable", func() bool { _, err := chip.ReadAverage(); return err == nil }())
	check("averaging", chip.SetAveraging(4) == nil && func() bool { v, err := chip.GetAveraging(); return err == nil && v == 4 }())
	check("averaging_reset", chip.SetAveraging(0) == nil)
	check("vin_uv_warn", chip.SetVinUVWarn(fp(8.0)) == nil && func() bool {
		l, err := chip.GetLimits()
		return err == nil && l.VinUVWarn != nil && *l.VinUVWarn > 7.95 && *l.VinUVWarn < 8.05
	}())
	check("vin_uv_warn_disabled", chip.SetVinUVWarn(nil) == nil && func() bool { l, err := chip.GetLimits(); return err == nil && l.VinUVWarn == nil }())
	check("ot_warn", chip.SetOTWarn(fp(110.0)) == nil && func() bool {
		l, err := chip.GetLimits()
		return err == nil && l.OTWarn != nil && *l.OTWarn > 109.9 && *l.OTWarn < 110.1
	}())
	check("ot_warn_restore", chip.SetOTWarn(fp(125.0)) == nil)
	check("peak_cleared", chip.ClearPowerPeak() == nil && func() bool { v, err := chip.PowerPeak(); return err == nil && v >= 0 }())
	check("clear_faults", chip.ClearFaults() == nil)
	check("status_word_readable", func() bool { _, err := chip.StatusWord(); return err == nil }())
	check("diagnostic_readable", func() bool { _, err := chip.DiagnosticWord(); return err == nil }())
	check("capability", func() bool { v, err := chip.Capability(); return err == nil && v == 0xB0 }())

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

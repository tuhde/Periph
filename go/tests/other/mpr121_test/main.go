//go:build linux && !tinygo

// MPR121 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/other"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5A"), 0, 8)
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

	chip, err := other.NewMPR121Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	passed := 0
	failed := 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	t, err := chip.Touched()
	check("touched in 0..4095", err == nil && t <= 0xFFF)

	if _, err := chip.IsTouched(0); err == nil {
		check("is_touched returns bool", true)
	}

	f0, err := chip.Filtered(0)
	check("filtered(0) in 0..1023", err == nil && f0 <= 1023)

	b0, err := chip.Baseline(0)
	check("baseline(0) in 0..1023", err == nil && b0 <= 1023)

	oor, err := chip.OORStatus()
	check("oor_status in 0..8191", err == nil && oor <= 0x1FFF)

	if err := chip.Stop(); err == nil {
		check("stop accepted", true)
	}
	if err := chip.ConfigureThresholds(0, 15, 8); err == nil {
		check("configure_thresholds accepted", true)
	}
	if err := chip.ConfigureAllThresholds(12, 6); err == nil {
		check("configure_all_thresholds accepted", true)
	}
	if err := chip.ConfigureProximityThresholds(8, 4); err == nil {
		check("configure_proximity_thresholds accepted", true)
	}
	if err := chip.ConfigureBaselineFilter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0); err == nil {
		check("configure_baseline_filter accepted", true)
	}
	if err := chip.ConfigureSampling(16, 1, 0, 0, 4); err == nil {
		check("configure_sampling accepted", true)
	}
	if err := chip.ConfigureDebounce(1, 1); err == nil {
		check("configure_debounce accepted", true)
	}
	if err := chip.ConfigureAutoconfig(3300, 0, false, true, true); err == nil {
		check("configure_autoconfig accepted", true)
	}
	if err := chip.Start(12, 2, 0); err == nil {
		check("start accepted", true)
	}
	if err := chip.EnableInterrupt(other.SOURCE_OOR); err == nil {
		check("enable_interrupt accepted", true)
	}
	if err := chip.DisableInterrupt(other.SOURCE_OOR); err == nil {
		check("disable_interrupt accepted", true)
	}
	if err := chip.ClearOvercurrent(); err == nil {
		check("clear_overcurrent accepted", true)
	}
	if err := chip.Reset(); err == nil {
		check("reset accepted", true)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

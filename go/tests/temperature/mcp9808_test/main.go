//go:build linux && !tinygo

// MCP9808 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x18"), 0, 8)
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

	m, err := temperature.NewMCP9808Minimal(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	t, err := m.ReadTemperature()
	check("temperature_plausible", err == nil && t >= -40 && t <= 125)

	full, err := temperature.NewMCP9808Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("set_resolution_0_5", full.SetResolution(0.5) == nil)
	r, err := full.GetResolution()
	check("resolution_0_5", err == nil && r == 0.5)
	check("set_resolution_0_0625", full.SetResolution(0.0625) == nil)
	r, err = full.GetResolution()
	check("resolution_0_0625", err == nil && r == 0.0625)

	check("set_upper_limit", full.SetUpperLimit(80) == nil)
	v, err := full.GetUpperLimit()
	check("upper_limit_roundtrip", err == nil && v == 80)
	check("set_lower_limit", full.SetLowerLimit(-10.25) == nil)
	v, err = full.GetLowerLimit()
	check("lower_limit_roundtrip", err == nil && v == -10.25)
	check("set_critical_limit", full.SetCriticalLimit(100) == nil)
	v, err = full.GetCriticalLimit()
	check("critical_limit_roundtrip", err == nil && v == 100)

	check("set_hysteresis", full.SetHysteresis(1.5) == nil)
	h, err := full.GetHysteresis()
	check("hysteresis_roundtrip", err == nil && h == 1.5)
	check("reset_hysteresis", full.SetHysteresis(0) == nil)

	check("shutdown", full.Shutdown() == nil)
	on, err := full.IsShutdown()
	check("is_shutdown", err == nil && on)
	check("wake", full.Wake() == nil)
	on, err = full.IsShutdown()
	check("is_awake", err == nil && !on)

	// Lower limit above ambient forces TA < TLOWER; the status bit is live
	// regardless of whether the Alert output is enabled.
	check("raise_lower_limit", full.SetLowerLimit(t+20) == nil)
	time.Sleep(300 * time.Millisecond)
	st, err := full.PollInterrupt()
	check("poll_interrupt_lower", err == nil && st&temperature.MCP9808SourceLower != 0)
	check("restore_lower_limit", full.SetLowerLimit(-10.25) == nil)
	time.Sleep(300 * time.Millisecond)
	st, err = full.PollInterrupt()
	check("poll_interrupt_clear", err == nil && st&temperature.MCP9808SourceLower == 0)

	check("configure_alert", full.ConfigureAlert(temperature.MCP9808AlertAll,
		temperature.MCP9808AlertComparator, temperature.MCP9808AlertActiveLow) == nil)
	check("enable_alert", full.EnableAlert() == nil)
	a, err := full.IsAlertAsserted()
	check("alert_not_asserted_in_window", err == nil && !a)
	check("disable_alert", full.DisableAlert() == nil)
	check("clear_interrupt", full.ClearInterrupt() == nil)

	// The lock bits are one-way until power-on reset and are never set here.
	l, err := full.IsCriticalLimitLocked()
	check("not_critical_locked", err == nil && !l)
	l, err = full.IsWindowLimitsLocked()
	check("not_window_locked", err == nil && !l)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

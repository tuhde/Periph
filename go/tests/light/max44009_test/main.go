//go:build linux && !tinygo

// MAX44009 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

var passed, failed int

func check(label string, cond bool) {
	if cond {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Println("FAIL", label)
		failed++
	}
}

func fail(what string, err error) {
	fmt.Fprintln(os.Stderr, what+":", err)
	os.Exit(2)
}

func near(a, b, tol float32) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < tol
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fail("I2C_BUS", err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x4A"), 0, 8)
	if err != nil {
		fail("I2C_ADDR", err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fail("connection", err)
	}
	defer conn.Close()

	sensor, err := light.NewMAX44009Minimal(conn)
	if err != nil {
		fail("init", err)
		os.Exit(2)
	}
	check("construct_minimal", sensor != nil)
	lx, _ := sensor.Lux()
	check("lux_in_range", lx >= 0 && lx <= 188006.4)

	full, err := light.NewMAX44009Full(conn)
	if err != nil {
		fail("init full", err)
		os.Exit(2)
	}
	e, _, over, _ := full.ReadRaw()
	check("read_raw_ranges", e <= 15 && over == (e == 15))
	full.SetContinuous(true)
	cfg, _ := full.GetConfig()
	check("continuous_on", cfg.Continuous)
	full.SetContinuous(false)
	cfg, _ = full.GetConfig()
	check("continuous_off", !cfg.Continuous)
	full.SetManual(100, false)
	cfg, _ = full.GetConfig()
	check("manual_100ms", cfg.Manual && cfg.IntegrationMs == 100 && !cfg.DivideBy8)
	full.SetManual(25, true)
	cfg, _ = full.GetConfig()
	check("manual_25ms_divide", cfg.IntegrationMs == 25 && cfg.DivideBy8)
	full.SetAutomatic()
	cfg, _ = full.GetConfig()
	check("automatic", !cfg.Manual)
	full.SetThresholds(50, 500)
	lo, hi, _ := full.GetThresholds()
	check("thresholds_roundtrip", near(lo, 50, 10) && near(hi, 500, 100))
	full.SetThresholdDelay(2.0)
	delay, _ := full.GetThresholdDelay()
	check("threshold_delay", near(delay, 2.0, 0.05))
	full.EnableInterrupt()
	full.DisableInterrupt()
	status, _ := full.PollInterrupt()
	check("poll_interrupt_cleared", status == 0)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

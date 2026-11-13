//go:build tinygo

// MAX44009 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, cond bool) {
	if cond {
		println("PASS", label)
		passed++
	} else {
		println("FAIL", label)
		failed++
	}
}

func fail(what string, err error) {
	println(what+":", err)
}

func near(a, b, tol float32) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < tol
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, light.MAX44009I2CAddress, nil, nil)
	defer conn.Close()

	sensor, err := light.NewMAX44009Minimal(conn)
	if err != nil {
		fail("init", err)
		return
	}
	check("construct_minimal", sensor != nil)
	lx, _ := sensor.Lux()
	check("lux_in_range", lx >= 0 && lx <= 188006.4)

	full, err := light.NewMAX44009Full(conn)
	if err != nil {
		fail("init full", err)
		return
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

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}

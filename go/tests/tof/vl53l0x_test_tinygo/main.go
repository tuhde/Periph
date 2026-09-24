//go:build tinygo

// VL53L0X hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/tof"
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

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, tof.VL53L0XI2CAddress, nil, nil)
	defer conn.Close()

	m, err := tof.NewVL53L0XMinimal(conn)
	if err != nil {
		println("init:", err.Error())
		return
	}
	full, err := tof.NewVL53L0XFull(conn)
	if err != nil {
		println("init:", err.Error())
		return
	}

	d, err := m.Distance()
	check("distance_in_range", err == nil && d <= 8191)
	_ = m.RangeValid()

	id, err := full.ModelID()
	check("model_id", err == nil && id == 0xEE)
	rev, err := full.RevisionID()
	check("revision_id", err == nil && rev > 0)
	budget, err := full.TimingBudget()
	check("default_budget", err == nil && budget >= 20000 && budget <= 40000)

	_, _ = full.Distance()
	meas, err := full.ReadMeasurement()
	check("measurement_record", err == nil && meas.RangeStatus <= 15 && meas.SignalRateMCPS >= 0)

	check("set_budget", full.SetTimingBudget(50000) == nil)
	budget, err = full.TimingBudget()
	check("budget_roundtrip", err == nil && budget > 49700 && budget < 50300)
	check("set_signal_rate", full.SetSignalRateLimit(0.1) == nil)
	limit, err := full.SignalRateLimit()
	check("signal_rate_roundtrip", err == nil && limit > 0.09 && limit < 0.11)
	check("vcsel_pre", full.SetVcselPulsePeriod(tof.VL53L0XPreRange, 18) == nil)
	check("vcsel_final", full.SetVcselPulsePeriod(tof.VL53L0XFinalRange, 14) == nil)
	pre, _ := full.VcselPulsePeriod(tof.VL53L0XPreRange)
	fin, _ := full.VcselPulsePeriod(tof.VL53L0XFinalRange)
	check("vcsel_roundtrip", pre == 18 && fin == 14)
	check("profile_default", full.SetProfile(tof.VL53L0XProfileDefault) == nil)
	pre, _ = full.VcselPulsePeriod(tof.VL53L0XPreRange)
	budget, _ = full.TimingBudget()
	check("profile_default_applied", pre == 14 && budget > 32700 && budget < 33300)

	original, _ := full.Offset()
	check("set_offset", full.SetOffset(-10.25) == nil)
	off, err := full.Offset()
	check("offset_roundtrip", err == nil && off == -10.25)
	_ = full.SetOffset(original)

	check("set_thresholds", full.SetInterruptThresholds(100, 800) == nil)
	lo, hi, err := full.InterruptThresholds()
	check("thresholds_roundtrip", err == nil && lo == 100 && hi == 800)

	// Back-to-back continuous ranging, then timed mode.
	check("start_continuous", full.StartContinuous(0) == nil)
	ok := true
	for i := 0; i < 3; i++ {
		r, err := full.ReadContinuous()
		ok = ok && err == nil && r <= 8191
	}
	check("continuous_readings", ok)
	_ = full.StopContinuous()
	time.Sleep(50 * time.Millisecond)
	_, _ = full.PollInterrupt()
	check("start_timed", full.StartContinuous(100) == nil)
	ok = true
	for i := 0; i < 2; i++ {
		r, err := full.ReadContinuous()
		ok = ok && err == nil && r <= 8191
	}
	check("timed_readings", ok)
	_ = full.StopContinuous()
	time.Sleep(150 * time.Millisecond)
	_, _ = full.PollInterrupt()

	_ = full.StartContinuous(0)
	time.Sleep(100 * time.Millisecond)
	st, err := full.PollInterrupt()
	check("poll_interrupt_new_sample", err == nil && st == tof.VL53L0XSourceNewSampleReady)
	_ = full.StopContinuous()
	time.Sleep(50 * time.Millisecond)
	_, _ = full.PollInterrupt()

	check("recalibrate", full.Recalibrate() == nil)
	d, err = full.Distance()
	check("recalibrate_then_distance", err == nil && d <= 8191)

	println("===DONE:", passed, "passed,", failed, "failed===")
}

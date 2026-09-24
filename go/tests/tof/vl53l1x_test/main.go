//go:build linux && !tinygo

// VL53L1X hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/tof"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x29"), 0, 8)
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

	m, err := tof.NewVL53L1XMinimal(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	full, err := tof.NewVL53L1XFull(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init full:", err)
		os.Exit(2)
	}

	d, err := m.Distance()
	check("distance_completes", err == nil)
	_ = m.RangeValid()
	_ = d

	id, err := full.ModelID()
	check("model_id", err == nil && id == 0xEA)
	mt, err := full.ModuleType()
	check("module_type", err == nil && mt == 0xCC)
	rev, err := full.RevisionID()
	check("revision_id", err == nil && rev > 0)
	budget, err := full.TimingBudget()
	check("default_budget", err == nil && budget == 100000)
	mode, err := full.DistanceMode()
	check("default_mode", err == nil && mode == tof.VL53L1XDistanceModeLong)

	_, _ = full.Distance()
	meas, err := full.ReadMeasurement()
	check("measurement_record", err == nil && meas.SignalRateMCPS >= 0 && meas.EffectiveSpadCount >= 0)

	_ = full.SetTimingBudget(50000)
	budget, err = full.TimingBudget()
	check("budget_roundtrip", err == nil && budget == 50000)
	_ = full.SetDistanceMode(tof.VL53L1XDistanceModeShort)
	mode, _ = full.DistanceMode()
	budget, _ = full.TimingBudget()
	check("mode_short", mode == tof.VL53L1XDistanceModeShort && budget == 50000)
	_, err = full.Distance()
	check("short_distance", err == nil)
	_ = full.SetDistanceMode(tof.VL53L1XDistanceModeLong)
	_ = full.SetTimingBudget(100000)

	_ = full.SetSignalRateLimit(0.5)
	limit, err := full.SignalRateLimit()
	check("signal_rate_roundtrip", err == nil && limit == 0.5)
	_ = full.SetSignalRateLimit(1.0)
	_ = full.SetSigmaThreshold(60)
	sigma, err := full.SigmaThreshold()
	check("sigma_roundtrip", err == nil && sigma == 60)
	_ = full.SetSigmaThreshold(90)

	_ = full.SetROI(8, 8)
	w, h, err := full.ROI()
	check("roi_roundtrip", err == nil && w == 8 && h == 8)
	_, _ = full.OpticalCenter()
	_ = full.SetROI(16, 16)
	c, _ := full.ROICenter()
	w, h, _ = full.ROI()
	check("roi_restored", w == 16 && h == 16 && c == 199)

	original, _ := full.Offset()
	_ = full.SetOffset(-10.25)
	off, err := full.Offset()
	check("offset_roundtrip", err == nil && off == -10.25)
	_ = full.SetOffset(original)
	_ = full.SetCrosstalkCompensation(0.01)
	xt, err := full.CrosstalkCompensation()
	check("crosstalk_roundtrip", err == nil && xt > 0.0099 && xt < 0.0101)
	_ = full.SetCrosstalkCompensation(0)

	_ = full.SetInterruptThresholds(100, 800)
	lo, hi, err := full.InterruptThresholds()
	check("thresholds_roundtrip", err == nil && lo == 100 && hi == 800)

	_ = full.StartContinuous(150)
	period, err := full.InterMeasurement()
	check("inter_measurement", err == nil && period >= 148 && period <= 150)
	contOK := true
	for i := 0; i < 3; i++ {
		_, err := full.ReadContinuous()
		contOK = contOK && err == nil
	}
	check("continuous_readings", contOK)
	time.Sleep(200 * time.Millisecond)
	st, err := full.PollInterrupt()
	check("poll_interrupt_new_sample", err == nil && st == tof.VL53L1XSourceNewSampleReady)
	_ = full.StopContinuous()
	time.Sleep(200 * time.Millisecond)
	_, _ = full.PollInterrupt()

	check("recalibrate", full.Recalibrate() == nil)
	_, err = full.Distance()
	check("recalibrate_then_distance", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

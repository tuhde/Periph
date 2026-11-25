//go:build linux && !tinygo

// VL6180X hardware test — Linux host.
package main

import (
	"errors"
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

	conn, err := connection.NewI2CConnectionWithWidth(bus, uint8(addr), 2, nil, nil)
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

	m, err := tof.NewVL6180XMinimal(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	full, err := tof.NewVL6180XFull(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("init", true)

	_, err = m.ReadRange()
	check("read_range_completes", err == nil || errors.Is(err, tof.ErrVL6180XRange))

	id, err := full.ModelID()
	check("model_id", err == nil && id == 0xB4)
	major, _, err := full.Revision()
	check("revision", err == nil && major >= 1)
	_, code, err := full.ReadRangeStatus()
	check("range_status", err == nil && code <= 15)

	_, err = full.ReadAlsRaw()
	check("als_raw", err == nil)
	lux, err := full.ReadLux()
	check("lux_positive", err == nil && lux >= 0)
	for _, c := range []uint8{0, 6, 7} {
		check("set_als_gain", full.SetAlsGain(c) == nil)
		_, err = full.ReadLux()
		check("lux_at_gain", err == nil)
	}
	check("restore_gain", full.SetAlsGain(6) == nil)
	check("set_als_integration_time", full.SetAlsIntegrationTime(50) == nil)
	_, err = full.ReadLux()
	check("lux_50ms", err == nil)
	check("restore_integration", full.SetAlsIntegrationTime(100) == nil)
	check("set_als_scaler", full.SetAlsScaler(1) == nil)

	check("set_range_offset", full.SetRangeOffset(3) == nil)
	off, err := full.GetRangeOffset()
	check("offset_roundtrip", err == nil && off == 3)
	check("restore_offset", full.SetRangeOffset(0) == nil)
	check("set_crosstalk_rate", full.SetCrosstalkRate(0) == nil)
	check("set_max_convergence_time", full.SetMaxConvergenceTime(49) == nil)
	check("set_averaging_period", full.SetAveragingPeriod(48) == nil)
	check("recalibrate_vhv", full.RecalibrateVHV() == nil)
	_, _, err = full.ReadRangeStatus()
	check("range_after_recalibration", err == nil)

	check("start_continuous_range", full.StartContinuousRange(50) == nil)
	contOK := true
	for i := 0; i < 5; i++ {
		_, err = full.ReadRangeContinuous()
		contOK = contOK && (err == nil || errors.Is(err, tof.ErrVL6180XRange))
	}
	check("continuous_range", contOK)
	check("stop_continuous_range", full.StopContinuousRange() == nil)
	time.Sleep(200 * time.Millisecond)

	check("set_range_thresholds", full.SetRangeThresholds(30, 80) == nil)
	check("enable_interrupt", full.EnableInterrupt(tof.VL6180XSourceRange, tof.VL6180XModeOutOfWindow) == nil)
	check("restart_continuous", full.StartContinuousRange(50) == nil)
	time.Sleep(300 * time.Millisecond)
	s, err := full.PollInterrupt()
	check("poll_interrupt_mask", err == nil && s <= 7)
	_ = full.StopContinuousRange()
	time.Sleep(200 * time.Millisecond)
	check("disable_interrupt", full.DisableInterrupt(tof.VL6180XSourceRange) == nil)
	check("restore_interrupt", full.EnableInterrupt(tof.VL6180XSourceRange, tof.VL6180XModeNewSampleReady) == nil)
	_, _ = full.PollInterrupt()

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

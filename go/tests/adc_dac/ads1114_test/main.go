//go:build linux && !tinygo

// ADS1114 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func abs32(x int32) int32 {
	if x < 0 {
		return -x
	}
	return x
}

func absf(x float32) float32 {
	if x < 0 {
		return -x
	}
	return x
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x48"), 0, 8)
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
	fatal := func(label string, err error) {
		fmt.Println("FAIL", label, err)
		os.Exit(1)
	}

	m, err := adcdac.NewADS1114Minimal(conn)
	if err != nil {
		fatal("init", err)
	}
	full, err := adcdac.NewADS1114Full(conn)
	if err != nil {
		fatal("init", err)
	}
	check("construct_minimal", m != nil)
	raw, err := m.ReadRaw()
	raw2, err2 := m.ReadRaw()
	check("read_raw_stable", err == nil && err2 == nil && abs32(int32(raw)-int32(raw2)) < 4000)
	v, err := m.ReadVoltage()
	check("read_voltage_matches_raw", err == nil && absf(v-float32(raw)*0.0000625) < 0.25)

	check("set_data_rate", full.SetDataRate(adcdac.ADS1114DR860) == nil)
	check("data_rate_roundtrip", full.GetDataRate() == 7 && full.DataRateSps() == 860)
	check("set_gain", full.SetGain(adcdac.ADS1114PGA4096) == nil)
	check("gain_roundtrip", full.GetGain() == 1 && full.FullScale() == 4.096)
	full.SetGain(adcdac.ADS1114PGA2048)
	check("start_continuous", full.StartContinuous() == nil)
	time.Sleep(50 * time.Millisecond)
	cont, err := full.ReadContinuousRaw()
	check("continuous_matches_single_shot", err == nil && abs32(int32(cont)-int32(raw)) < 8000)
	cfg, _ := full.ReadConfig()
	check("continuous_config_mode", cfg&0x0100 == 0)
	check("stop_continuous", full.StopContinuous() == nil)
	cfg, _ = full.ReadConfig()
	check("single_shot_config_mode", cfg&0x0100 != 0)
	check("set_low_threshold_raw", full.SetLowThresholdRaw(-1000) == nil)
	check("set_high_threshold_raw", full.SetHighThresholdRaw(1000) == nil)
	lo, hi, err := full.GetThresholdsRaw()
	check("threshold_roundtrip", err == nil && lo == -1000 && hi == 1000)
	check("configure_comparator", full.ConfigureComparator(adcdac.ADS1114CompWindow, adcdac.ADS1114ActiveLow, false, adcdac.ADS1114Queue4) == nil)
	cfg, _ = full.ReadConfig()
	check("comparator_bits", cfg&0x0017 == 0x0012)
	check("disable_comparator", full.DisableComparator() == nil)
	cfg, _ = full.ReadConfig()
	check("comparator_disabled", cfg&0x0003 == 0x0003)
	check("reset", full.Reset() == nil)
	cfg, _ = full.ReadConfig()
	check("reset_config", cfg&0x7FFF == 0x0583)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

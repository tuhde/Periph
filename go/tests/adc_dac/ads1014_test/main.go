//go:build linux && !tinygo

// ADS1014 hardware test — Linux host.
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
	mustInit := func(err error) {
		if err != nil {
			fmt.Fprintln(os.Stderr, "init:", err)
			os.Exit(2)
		}
	}

	m, err := adcdac.NewADS1014Minimal(conn) // Create ADS1014 driver, (conn) → (*ADS1014Minimal, error)
	mustInit(err)
	raw, err := m.ReadRaw() // Read raw code, () → (int16, error)
	check("raw_in_range", err == nil && raw >= -2048 && raw <= 2047)
	v, err := m.ReadVoltage() // Read voltage, () → (float32 V, error)
	check("voltage_plausible", err == nil && v > -7 && v < 7)

	full, err := adcdac.NewADS1014Full(conn) // Create ADS1014 Full driver, (conn) → (*ADS1014Full, error)
	mustInit(err)
	check("reset", full.Reset() == nil) // Reset to power-up defaults, () → error
	cfg, err := full.ReadConfig()       // Read CONFIG register, () → (uint16, error)
	check("reset_config", err == nil && cfg&0x7FFF == 0x0583)

	check("set_gain", full.SetGain(adcdac.ADS1014PGA0_256) == nil) // Set PGA, (pga 0–5) → error
	check("gain_roundtrip", full.GetGain() == adcdac.ADS1014PGA0_256 && full.FullScale() == 0.256)
	_, err = full.ReadRaw()
	cfg, err = full.ReadConfig()
	check("config_gain_bits", err == nil && (cfg>>9)&0x7 == uint16(adcdac.ADS1014PGA0_256))
	check("restore_gain", full.SetGain(adcdac.ADS1014PGA2_048) == nil)

	check("set_data_rate", full.SetDataRate(adcdac.ADS1014DR250) == nil) // Set data rate, (dr 0–6) → error
	check("dr_roundtrip", full.GetDataRate() == adcdac.ADS1014DR250 && full.DataRateSPS() == 250)
	_, err = full.ReadRaw()
	cfg, err = full.ReadConfig()
	check("config_dr_bits", err == nil && (cfg>>5)&0x7 == uint16(adcdac.ADS1014DR250))
	check("restore_data_rate", full.SetDataRate(adcdac.ADS1014DR1600) == nil)

	check("start_continuous", full.StartContinuous() == nil) // Start continuous conversion, () → error
	time.Sleep(50 * time.Millisecond)
	craw, err := full.ReadContinuousRaw() // Read latest code, () → (int16, error)
	check("continuous_raw_in_range", err == nil && craw >= -2048 && craw <= 2047)
	cfg, err = full.ReadConfig()
	check("continuous_mode_bit", err == nil && cfg&0x0100 == 0)
	check("stop_continuous", full.StopContinuous() == nil) // Stop continuous conversion, () → error
	cfg, err = full.ReadConfig()
	check("stop_mode_bit", err == nil && cfg&0x0100 != 0)

	check("set_low_threshold", full.SetLowThresholdRaw(-100) == nil)
	check("set_high_threshold", full.SetHighThresholdRaw(300) == nil)
	lo, hi, err := full.GetThresholdsRaw() // Read thresholds, () → (int16, int16, error)
	check("threshold_roundtrip", err == nil && lo == -100 && hi == 300)
	check("set_thresholds", full.SetThresholds(-0.2, 0.5) == nil)
	lo, hi, err = full.GetThresholdsRaw()
	check("threshold_volts_roundtrip", err == nil && lo == -200 && hi == 500)

	check("configure_comparator", full.ConfigureComparator(adcdac.ADS1014CompWindow, adcdac.ADS1014ActiveHigh, true, adcdac.ADS1014Queue2) == nil) // Configure comparator, (mode, polarity, latch, queue) → error
	cfg, err = full.ReadConfig()
	check("comparator_bits", err == nil && cfg&0x1F == 0x10|0x08|0x04|0x01)
	check("clear_alert", full.ClearAlert() == nil)
	check("disable_comparator", full.DisableComparator() == nil)
	cfg, err = full.ReadConfig()
	check("comparator_disabled", err == nil && cfg&0x3 == 3)

	check("enable_ready", full.EnableInterrupt(adcdac.ADS1014SourceConversionReady) == nil) // Route conversion-ready to ALERT/RDY, (source) → error
	lo, hi, err = full.GetThresholdsRaw()
	check("ready_thresholds", err == nil && lo == 0 && hi == -2048)
	cfg, err = full.ReadConfig()
	check("ready_que_00", err == nil && cfg&0x3 == 0)
	check("disable_interrupt", full.DisableInterrupt(adcdac.ADS1014SourceConversionReady) == nil)

	check("final_reset", full.Reset() == nil)
	cfg, err = full.ReadConfig()
	check("final_reset_config", err == nil && cfg&0x7FFF == 0x0583)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

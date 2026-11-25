//go:build tinygo

// ADS1013 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
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

func mustInit(err error) {
	if err != nil {
		println("init:", err.Error())
		for {
			time.Sleep(time.Second)
		}
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1013I2CAddress, nil, nil)
	defer conn.Close()

	m, err := adcdac.NewADS1013Minimal(conn) // Create ADS1013 driver, (conn) → (*ADS1013Minimal, error)
	mustInit(err)
	raw, err := m.ReadRaw() // Read raw code, () → (int16, error)
	check("raw_in_range", err == nil && raw >= -2048 && raw <= 2047)
	v, err := m.ReadVoltage() // Read voltage, () → (float32 V, error)
	check("voltage_plausible", err == nil && v > -7 && v < 7)

	full, err := adcdac.NewADS1013Full(conn) // Create ADS1013 Full driver, (conn) → (*ADS1013Full, error)
	mustInit(err)
	check("reset", full.Reset() == nil) // Reset to power-up defaults, () → error
	cfg, err := full.ReadConfig()       // Read CONFIG register, () → (uint16, error)
	check("reset_config", err == nil && cfg&0x7FFF == 0x0583)
	check("full_scale_fixed", full.FullScale() == 2.048 && full.LSB() == 0.001)

	check("set_data_rate", full.SetDataRate(adcdac.ADS1013DR250) == nil) // Set data rate, (dr 0–6) → error
	check("dr_roundtrip", full.GetDataRate() == adcdac.ADS1013DR250 && full.DataRateSPS() == 250)
	_, err = full.ReadRaw()
	cfg, err = full.ReadConfig()
	check("config_dr_bits", err == nil && (cfg>>5)&0x7 == uint16(adcdac.ADS1013DR250))
	check("restore_data_rate", full.SetDataRate(adcdac.ADS1013DR1600) == nil)

	check("start_continuous", full.StartContinuous() == nil) // Start continuous conversion, () → error
	time.Sleep(50 * time.Millisecond)
	craw, err := full.ReadContinuousRaw() // Read latest code, () → (int16, error)
	check("continuous_raw_in_range", err == nil && craw >= -2048 && craw <= 2047)
	cfg, err = full.ReadConfig()
	check("continuous_mode_bit", err == nil && cfg&0x0100 == 0)
	check("stop_continuous", full.StopContinuous() == nil) // Stop continuous conversion, () → error
	cfg, err = full.ReadConfig()
	check("stop_mode_bit", err == nil && cfg&0x0100 != 0)

	check("final_reset", full.Reset() == nil)
	cfg, err = full.ReadConfig()
	check("final_reset_config", err == nil && cfg&0x7FFF == 0x0583)

	println("===DONE:", passed, "passed,", failed, "failed===")
}

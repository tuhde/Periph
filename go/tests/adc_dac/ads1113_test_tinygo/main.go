//go:build tinygo

// ADS1113 hardware test — TinyGo (Raspberry Pi Pico W).
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

func fatal(label string, err error) {
	println("FAIL", label, err.Error())
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
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1113I2CAddress, nil, nil)
	defer conn.Close()

	m, err := adcdac.NewADS1113Minimal(conn)
	if err != nil {
		fatal("init", err)
	}
	full, err := adcdac.NewADS1113Full(conn)
	if err != nil {
		fatal("init", err)
	}
	check("construct_minimal", m != nil)
	raw, err := m.ReadRaw()
	raw2, err2 := m.ReadRaw()
	check("read_raw_stable", err == nil && err2 == nil && abs32(int32(raw)-int32(raw2)) < 4000)
	v, err := m.ReadVoltage()
	check("read_voltage_matches_raw", err == nil && absf(v-float32(raw)*0.0000625) < 0.25)

	check("set_data_rate", full.SetDataRate(adcdac.ADS1113DR860) == nil)
	check("data_rate_roundtrip", full.GetDataRate() == 7 && full.DataRateSps() == 860)
	check("start_continuous", full.StartContinuous() == nil)
	time.Sleep(50 * time.Millisecond)
	cont, err := full.ReadContinuousRaw()
	check("continuous_matches_single_shot", err == nil && abs32(int32(cont)-int32(raw)) < 8000)
	cfg, _ := full.ReadConfig()
	check("continuous_config_mode", cfg&0x0100 == 0)
	check("stop_continuous", full.StopContinuous() == nil)
	cfg, _ = full.ReadConfig()
	check("single_shot_config_mode", cfg&0x0100 != 0)
	check("reset", full.Reset() == nil)
	cfg, _ = full.ReadConfig()
	check("reset_config", cfg&0x7FFF == 0x0583)

	println("===DONE:", passed, "passed,", failed, "failed===")
}

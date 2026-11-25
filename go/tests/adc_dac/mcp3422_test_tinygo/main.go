//go:build tinygo

// MCP3422 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
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
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.MCP3422I2CAddress, nil, nil)
	defer conn.Close()

	adc, err := adcdac.NewMCP3422Minimal(conn)
	if err != nil {
		fail("init", err)
		return
	}
	check("construct_minimal", adc != nil)
	raw, err := adc.ReadRaw(0)
	check("raw_in_16_bit_range", err == nil && raw >= -32768 && raw <= 32767)
	v, err := adc.ReadVoltage(1)
	check("voltage_in_range", err == nil && v >= -2.048 && v <= 2.048)
	both, err := adc.ReadAll()
	check("read_all_two_values", err == nil && len(both) == 2)

	full, err := adcdac.NewMCP3422Full(conn)
	if err != nil {
		fail("init full", err)
		return
	}
	check("selection_accepted", full.SetResolution(12) == nil && full.SetGain(2) == nil)
	check("selection_roundtrip", full.GetResolution() == 12 && full.GetGain() == 2)
	check("full_scale", near(full.FullScale(), 1.024, 1e-6))
	r12, err := full.ReadRaw(0)
	check("one_shot_12_bit_range", err == nil && r12 >= -2048 && r12 <= 2047)
	full.SetGain(1)
	check("start_continuous", full.StartContinuous(0) == nil && full.IsContinuous())
	rc, err := full.ReadContinuousRaw()
	check("continuous_sample_in_range", err == nil && rc >= -2048 && rc <= 2047)
	cfg, err := full.ReadConfig()
	check("read_config_continuous_bit", err == nil && cfg&0x10 != 0)
	check("stopped", full.StopContinuous() == nil && !full.IsContinuous())
	scan, err := full.ReadScan([]int{0, 1})
	check("read_scan_two_values", err == nil && len(scan) == 2)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}

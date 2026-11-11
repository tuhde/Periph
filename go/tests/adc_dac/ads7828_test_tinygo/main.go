//go:build tinygo

// ADS7828 hardware test — TinyGo (Raspberry Pi Pico W).
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

func absf(x float32) float32 {
	if x < 0 {
		return -x
	}
	return x
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS7828I2CAddress, nil, nil)
	defer conn.Close()
	time.Sleep(2 * time.Second) // let USB CDC enumerate

	d, err := adcdac.NewADS7828Full(conn, 0)
	if err != nil {
		println("FAIL init", err.Error())
		return
	}
	if err := d.Init(); err != nil {
		println("FAIL init", err.Error())
		return
	}

	raw, err := d.ReadRaw(0)
	check("read_raw_in_range", err == nil && raw <= 4095)
	raw, err = d.ReadRaw(99)
	check("read_raw_clamped", err == nil && raw <= 4095)
	v, err := d.ReadVoltage(1)
	check("read_voltage_in_range", err == nil && v >= 0 && v <= 2.5)
	all, err := d.ReadAll()
	allOK := err == nil
	for _, x := range all {
		if x < 0 || x > 2.5 {
			allOK = false
		}
	}
	check("read_all_in_range", allOK)
	draw, err := d.ReadDifferentialRaw(0, false)
	check("read_differential_raw_in_range", err == nil && draw <= 4095)
	dv, err := d.ReadDifferential(1, true)
	check("read_differential_inverted_nonneg", err == nil && dv >= 0)
	sv, err := d.ReadSignedDifferential(2)
	check("read_signed_differential_in_range", err == nil && sv >= -2.5 && sv <= 2.5)
	check("lsb", absf(d.LSB()-2.5/4096) < 1e-6)
	check("internal_reference", d.UsesInternalReference() && d.GetReference() == 2.5)
	scan, err := d.ReadScan([]int{0, 3, 7})
	scanOK := err == nil && len(scan) == 3
	for _, r := range scan {
		if r > 4095 {
			scanOK = false
		}
	}
	check("read_scan_in_range", scanOK)
	check("set_power_mode_ok", d.SetPowerMode(adcdac.ADS7828RefOnADCOn) == nil)
	check("internal_reference_rejects_pd1_clear", d.SetPowerMode(adcdac.ADS7828RefOffADCOn) != nil)
	check("set_reference_external", d.SetReference(5.0) == nil && !d.UsesInternalReference() && d.GetReference() == 5.0)
	check("set_reference_internal", d.SetReference(0) == nil && d.UsesInternalReference())
	check("power_down", d.PowerDown() == nil)
	raw, err = d.ReadRaw(0)
	check("read_after_power_down", err == nil && raw <= 4095)

	println("===DONE:", passed, "passed,", failed, "failed===")
}

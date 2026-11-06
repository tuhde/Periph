//go:build tinygo

// AD5243 hardware test — TinyGo (Raspberry Pi Pico W).
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

func get(p *adcdac.AD5243Minimal, ch uint8) uint8 {
	v, _ := p.GetWiper(ch)
	return v
}

func res(p *adcdac.AD5243Minimal, ch uint8) float32 {
	v, _ := p.GetResistance(ch)
	return v
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
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.AD5243I2CAddress, nil, nil)
	defer conn.Close()

	pot, err := adcdac.NewAD5243Minimal(conn, adcdac.AD5243DefaultRAB, adcdac.AD5243DefaultRWiper)
	if err != nil {
		fail("init", err)
		return
	}
	check("construct_minimal", pot != nil)
	check("wiper0_roundtrip", pot.SetWiper(0, 100) == nil && get(pot, 0) == 100)
	check("wiper1_roundtrip", pot.SetWiper(1, 200) == nil && get(pot, 1) == 200)
	check("wiper0_independent", get(pot, 0) == 100)
	pot.SetWiper(0, 300)
	check("wiper_clamps_255", get(pot, 0) == 255)
	check("set_resistance", pot.SetResistance(0, 5000.0) == nil && near(res(pot, 0), 5000.0, 50.0))

	full, err := adcdac.NewAD5243Full(conn, adcdac.AD5243DefaultRAB, adcdac.AD5243DefaultRWiper)
	if err != nil {
		fail("init full", err)
		return
	}
	full.SetBoth(10, 250)
	check("set_both", get(&full.AD5243Minimal, 0) == 10 && get(&full.AD5243Minimal, 1) == 250)
	full.SetRatio(0, 0.5)
	ratio, _ := full.GetRatio(0)
	check("ratio_half", get(&full.AD5243Minimal, 0) == 128 && near(ratio, 0.5, 0.01))
	full.SetResistanceWA(1, 3000.0)
	rwa, _ := full.GetResistanceWA(1)
	check("set_resistance_wa", near(rwa, 3000.0, 50.0))
	full.SetWiper(0, 100)
	n, _ := full.Increment(0, 5)
	check("increment", n == 105 && get(&full.AD5243Minimal, 0) == 105)
	n, _ = full.Increment(0, -500)
	check("increment_clamps", n == 0)

	full.SetWiper(1, 77)
	full.Shutdown(1)
	sd, _ := full.IsShutdown(1)
	check("is_shutdown", sd)
	check("shutdown_keeps_rdac", get(&full.AD5243Minimal, 1) == 77)
	full.Resume(1)
	sd, _ = full.IsShutdown(1)
	check("resume", !sd && get(&full.AD5243Minimal, 1) == 77)

	full.SetBoth(1, 2)
	full.Reset()
	check("reset_midscale", get(&full.AD5243Minimal, 0) == 128 && get(&full.AD5243Minimal, 1) == 128)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}

//go:build tinygo

// AD5251 hardware test — TinyGo (Raspberry Pi Pico W).
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

func get(p *adcdac.AD5251Minimal, ch uint8) uint8 {
	v, _ := p.GetWiper(ch)
	return v
}

func res(p *adcdac.AD5251Minimal, ch uint8) float32 {
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
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.AD5251I2CAddress, nil, nil)
	defer conn.Close()

	pot, err := adcdac.NewAD5251Minimal(conn, adcdac.AD5251DefaultRAB, adcdac.AD5251DefaultRWiper)
	if err != nil {
		fail("init", err)
		return
	}
	check("construct_minimal", pot != nil)
	check("wiper0_roundtrip", pot.SetWiper(0, 40) == nil && get(pot, 0) == 40)
	check("wiper1_roundtrip", pot.SetWiper(1, 60) == nil && get(pot, 1) == 60)
	check("wiper0_independent", get(pot, 0) == 40)
	pot.SetWiper(0, 300)
	check("wiper_clamps_255", get(pot, 0) == 63)
	check("set_resistance", pot.SetResistance(0, 25000.0) == nil && near(res(pot, 0), 25000.0, 800.0))

	full, err := adcdac.NewAD5251Full(conn, adcdac.AD5251DefaultRAB, adcdac.AD5251DefaultRWiper)
	if err != nil {
		fail("init full", err)
		return
	}
	full.SetBoth(10, 58)
	check("set_both", get(&full.AD5251Minimal, 0) == 10 && get(&full.AD5251Minimal, 1) == 58)
	full.SetRatio(0, 0.5)
	ratio, _ := full.GetRatio(0)
	check("ratio_half", get(&full.AD5251Minimal, 0) == 32 && near(ratio, 0.5, 0.01))
	full.SetResistanceWA(1, 15000.0)
	rwa, _ := full.GetResistanceWA(1)
	check("set_resistance_wa", near(rwa, 15000.0, 800.0))
	full.SetWiper(0, 20)
	full.Increment(0)
	check("increment", get(&full.AD5251Minimal, 0) == 21)
	full.Decrement(0)
	check("decrement", get(&full.AD5251Minimal, 0) == 20)
	full.Double(0)
	check("double", get(&full.AD5251Minimal, 0) == 40)
	full.Halve(0)
	check("halve", get(&full.AD5251Minimal, 0) == 20)
	full.SetBoth(10, 20)
	full.Increment(adcdac.AD5251Both)
	check("increment_both", get(&full.AD5251Minimal, 0) == 11 && get(&full.AD5251Minimal, 1) == 21)

	full.SetBoth(40, 60)
	full.Store(adcdac.AD5251Both)
	full.SetBoth(1, 2)
	full.Restore(adcdac.AD5251Both)
	check("store_restore", get(&full.AD5251Minimal, 0) == 40 && get(&full.AD5251Minimal, 1) == 60)
	full.SetBoth(1, 2)
	full.Reset()
	check("reset_restores_eemem", get(&full.AD5251Minimal, 0) == 40 && get(&full.AD5251Minimal, 1) == 60)

	full.WriteEEMEM(15, 0xA5)
	e, _ := full.ReadEEMEM(15)
	check("eemem_roundtrip", e == 0xA5)
	tol, _ := full.ReadTolerance(0)
	check("tolerance_range", tol > -50 && tol < 50)
	rab, _ := full.RABActual(0)
	check("rab_actual", near(rab, 50000.0*(1+tol/100), 0.5))
	check("nop", full.Nop() == nil)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}

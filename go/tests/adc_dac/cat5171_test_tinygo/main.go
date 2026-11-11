//go:build tinygo

// CAT5171 hardware test — TinyGo (Raspberry Pi Pico W).
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

func get(p *adcdac.CAT5171Minimal) uint8 {
	v, _ := p.GetWiper()
	return v
}

func res(p *adcdac.CAT5171Minimal) float32 {
	v, _ := p.GetResistance()
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
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.CAT5171I2CAddress, nil, nil)
	defer conn.Close()

	pot, err := adcdac.NewCAT5171Minimal(conn, adcdac.CAT5171DefaultRAB, adcdac.CAT5171DefaultRWiper)
	if err != nil {
		fail("init", err)
		return
	}
	check("construct_minimal", pot != nil)
	check("wiper_roundtrip", pot.SetWiper(100) == nil && get(pot) == 100)
	pot.SetWiper(300)
	check("wiper_clamps_255", get(pot) == 255)
	check("set_resistance", pot.SetResistance(50000.0) == nil && near(res(pot), 50000.0, 500.0))

	full, err := adcdac.NewCAT5171Full(conn, adcdac.CAT5171DefaultRAB, adcdac.CAT5171DefaultRWiper)
	if err != nil {
		fail("init full", err)
		return
	}
	full.SetRatio(0.5)
	ratio, _ := full.GetRatio()
	check("ratio_half", get(&full.CAT5171Minimal) == 128 && near(ratio, 0.5, 0.01))
	full.SetResistanceWA(30000.0)
	rwa, _ := full.GetResistanceWA()
	check("set_resistance_wa", near(rwa, 30000.0, 500.0))
	full.SetWiper(100)
	n, _ := full.Increment(5)
	check("increment", n == 105 && get(&full.CAT5171Minimal) == 105)
	n, _ = full.Increment(-500)
	check("increment_clamps", n == 0)

	full.SetWiper(77)
	full.Shutdown()
	check("is_shutdown", full.IsShutdown())
	check("shutdown_keeps_wcr", get(&full.CAT5171Minimal) == 77)
	full.Resume()
	check("resume", !full.IsShutdown() && get(&full.CAT5171Minimal) == 77)

	full.SetWiper(10)
	full.Midscale()
	check("midscale", get(&full.CAT5171Minimal) == 128)

	full.SetWiper(1)
	full.Reset()
	check("reset_midscale", get(&full.CAT5171Minimal) == 128)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}

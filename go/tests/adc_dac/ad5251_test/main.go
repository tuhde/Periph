//go:build linux && !tinygo

// AD5251 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

var passed, failed int

func check(label string, cond bool) {
	if cond {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Println("FAIL", label)
		failed++
	}
}

func fail(what string, err error) {
	fmt.Fprintln(os.Stderr, what+":", err)
	os.Exit(2)
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fail("I2C_BUS", err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x2C"), 0, 8)
	if err != nil {
		fail("I2C_ADDR", err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fail("connection", err)
	}
	defer conn.Close()

	pot, err := adcdac.NewAD5251Minimal(conn, adcdac.AD5251DefaultRAB, adcdac.AD5251DefaultRWiper)
	if err != nil {
		fail("init", err)
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

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

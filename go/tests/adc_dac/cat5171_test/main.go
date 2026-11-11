//go:build linux && !tinygo

// CAT5171 hardware test — Linux host.
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

	pot, err := adcdac.NewCAT5171Minimal(conn, adcdac.CAT5171DefaultRAB, adcdac.CAT5171DefaultRWiper)
	if err != nil {
		fail("init", err)
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

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

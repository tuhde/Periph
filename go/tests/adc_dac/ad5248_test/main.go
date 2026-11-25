//go:build linux && !tinygo

// AD5248 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the AD5248 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x2C"), 0, 8)
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

	chip := adcdac.NewAD5248Full(conn, 10000, 160)

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

	// --- Wiper write / readback ---
	chip.SetWiper(0, 100)
	v, err := chip.GetWiper(0)
	check("set/get wiper ch0", err == nil && v == 100)
	chip.SetWiper(1, 200)
	v, err = chip.GetWiper(1)
	check("set/get wiper ch1", err == nil && v == 200)
	v, err = chip.GetWiper(0)
	check("ch0 unchanged by ch1 write", err == nil && v == 100)

	// --- Resistance ---
	chip.SetResistance(0, 5320)
	v, err = chip.GetWiper(0)
	check("set_resistance midscale code", err == nil && v == 128)
	r, err := chip.GetResistance(0)
	check("get_resistance ~5320", err == nil && r > 5319 && r < 5321)

	// --- Ratio / increment / both ---
	chip.SetBoth(10, 20)
	v, _ = chip.GetWiper(0)
	check("set_both ch0", v == 10)
	v, _ = chip.GetWiper(1)
	check("set_both ch1", v == 20)
	chip.SetRatio(0, 0.5)
	ratio, err := chip.GetRatio(0)
	check("get_ratio ~0.5", err == nil && ratio > 0.49 && ratio < 0.51)
	chip.SetWiper(0, 250)
	n, err := chip.Increment(0, 10)
	check("increment clamps", err == nil && n == 255)

	// --- Shutdown keeps RDAC ---
	chip.SetWiper(1, 77)
	chip.Shutdown(1)
	down, _ := chip.IsShutdown(1)
	check("is_shutdown", down)
	chip.Resume(1)
	v, _ = chip.GetWiper(1)
	check("RDAC kept across shutdown", v == 77)

	// --- Reset ---
	chip.Reset()
	v, _ = chip.GetWiper(0)
	check("reset ch0", v == 0x80)
	v, _ = chip.GetWiper(1)
	check("reset ch1", v == 0x80)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

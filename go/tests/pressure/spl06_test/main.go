//go:build linux && !tinygo

// SPL06 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the SPL06 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x77"), 0, 8)
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

	chip, _ := pressure.NewSPL06Full(conn)

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	check("init", chip.Init() == nil)
	co := chip.Coefficients()
	check("coefficients_decoded", co.C0 != 0 || co.C1 != 0 || co.C00 != 0 || co.C10 != 0)
	m, err := chip.Read()
	check("read_ok", err == nil)
	check("read_pressure_range", m.PressurePa > 30000 && m.PressurePa < 110000)
	check("read_temperature_range", m.TemperatureC > -40 && m.TemperatureC < 85)
	p, err := chip.ReadPressure()
	check("read_pressure_single", err == nil && p > 30000 && p < 110000)
	t, err := chip.ReadTemperature()
	check("read_temperature_single", err == nil && t > -40 && t < 85)
	praw, traw, err := chip.ReadRaw()
	check("read_raw", err == nil)
	c, err := chip.Compensate(praw, traw, 16, 1)
	check("compensate_raw", err == nil && c.PressurePa > 30000 && c.PressurePa < 110000)
	alt, err := chip.Altitude(101325)
	check("altitude_range", err == nil && alt > -500 && alt < 9000)
	for _, osr := range []int{1, 8, 128} {
		check("set_pressure_config", chip.SetPressureConfig(osr, 1) == nil)
		r, err := chip.ReadPressure()
		check("read_at_osr", err == nil && r > 30000 && r < 110000)
	}
	check("set_pressure_config_8hz", chip.SetPressureConfig(16, 8) == nil)
	check("set_temperature_config_8hz", chip.SetTemperatureConfig(1, 8) == nil)
	check("start_continuous", chip.StartContinuous(true, true) == nil)
	rc, err := chip.ReadContinuous()
	check("read_continuous", err == nil && rc.PressurePa > 30000 && rc.PressurePa < 110000)
	time.Sleep(300 * time.Millisecond)
	pr, _, err := chip.DataReady()
	check("data_ready", err == nil && pr)
	check("stop", chip.Stop() == nil)
	check("flush_fifo", chip.FlushFIFO() == nil)
	check("enable_fifo", chip.EnableFIFO() == nil)
	_ = chip.StartContinuous(true, true)
	time.Sleep(time.Second)
	_ = chip.Stop()
	empty, _, err := chip.FIFOStatus()
	check("fifo_not_empty", err == nil && !empty)
	entries, err := chip.ReadFIFO()
	check("fifo_read", err == nil && len(entries) >= 4)
	valuesOK := true
	for _, e := range entries {
		if e.Kind == pressure.SPL06KindPressure && (e.Value < 30000 || e.Value > 110000) {
			valuesOK = false
		}
	}
	check("fifo_values", valuesOK)
	empty, _, err = chip.FIFOStatus()
	check("fifo_empty_after_read", err == nil && empty)
	check("disable_fifo", chip.DisableFIFO() == nil)
	check("enable_interrupt", chip.EnableInterrupt(pressure.SPL06SourcePressure) == nil)
	_, _ = chip.PollInterrupt()
	_, _ = chip.Read()
	st, err := chip.PollInterrupt()
	check("poll_interrupt_pressure", err == nil && st&pressure.SPL06SourcePressure != 0)
	check("disable_interrupt", chip.DisableInterrupt(pressure.SPL06SourcePressure) == nil)
	check("reset", chip.Reset() == nil)
	r, err := chip.ReadPressure()
	check("read_after_reset", err == nil && r > 30000 && r < 110000)

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

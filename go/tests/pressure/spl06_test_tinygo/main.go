//go:build tinygo

// SPL06 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an SPL06 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	conn := connection.NewI2CConnection(i2c, 0x77, nil, nil)
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
}

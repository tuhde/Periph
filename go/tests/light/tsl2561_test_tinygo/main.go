//go:build tinygo

// TSL2561 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/light"
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

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, light.TSL2561I2CAddress, nil, nil)
	defer conn.Close()

	sensor, err := light.NewTSL2561Minimal(conn)
	if err != nil {
		fail("init", err)
		return
	}
	lx, err := sensor.ReadLux()
	check("read_lux_in_range", err == nil && lx >= 0 && lx <= 40000)

	full, err := light.NewTSL2561Full(conn)
	if err != nil {
		fail("init_full", err)
		return
	}
	id, err := full.ReadID()
	check("read_id_partno", err == nil && id>>4 == 1)
	on, err := full.IsPowered()
	check("is_powered", err == nil && on)
	_, _, err = full.ReadRaw()
	check("read_raw", err == nil)
	full.SetGain(16)
	full.SetIntegrationTime(101)
	check("gain_integration_roundtrip", full.Gain() == 16 && full.IntegrationTime() == 101)
	full.SetGain(1)
	full.SetIntegrationTime(402)
	lx, err = full.AutoRange()
	check("auto_range_lux", err == nil && lx >= 0)
	full.SetThresholds(100, 30000)
	lo, hi, err := full.Thresholds()
	check("thresholds_roundtrip", err == nil && lo == 100 && hi == 30000)
	check("configure_interrupt", full.ConfigureInterrupt(0, 0) == nil)
	check("clear_interrupt", full.ClearInterrupt() == nil)
	_, err = full.PollInterrupt()
	check("poll_interrupt", err == nil)
	full.PowerDown()
	on, _ = full.IsPowered()
	check("power_down", !on)
	full.PowerUp()
	on, _ = full.IsPowered()
	check("power_up", on)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}

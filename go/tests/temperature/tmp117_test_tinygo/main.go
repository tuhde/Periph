//go:build tinygo

// TMP117 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
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

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, temperature.TMP117I2CAddress, nil, nil)
	defer conn.Close()

	m, err := temperature.NewTMP117Minimal(conn)
	if err != nil {
		println("init:", err.Error())
		return
	}
	full, err := temperature.NewTMP117Full(conn)
	if err != nil {
		println("init:", err.Error())
		return
	}
	check("construct_minimal", m != nil)
	check("configure_fast", full.Configure(temperature.TMP117Continuous, 8, 0.125) == nil)
	time.Sleep(300 * time.Millisecond)
	t, err := m.ReadTemperature()
	check("temperature_plausible", err == nil && t >= -40 && t <= 125)

	check("configure_roundtrip_set", full.Configure(temperature.TMP117Continuous, 32, 4.0) == nil)
	cfg, err := full.GetConfig()
	check("config_roundtrip", err == nil && cfg == temperature.TMP117Config{Mode: temperature.TMP117Continuous, Averaging: 32, CycleSeconds: 4.0})
	check("configure_shutdown", full.Configure(temperature.TMP117Shutdown, 0, 0.0155) == nil)
	sd, err := full.IsShutdown()
	check("shutdown", err == nil && sd)
	check("trigger_one_shot", full.TriggerOneShot() == nil)
	time.Sleep(50 * time.Millisecond)
	ready, err := full.IsDataReady()
	check("one_shot_data_ready", err == nil && ready)
	sd, err = full.IsShutdown()
	check("one_shot_returns_to_shutdown", err == nil && sd)
	check("configure_default", full.Configure(temperature.TMP117Continuous, 8, 1.0) == nil)
	sd, err = full.IsShutdown()
	check("continuous", err == nil && !sd)

	check("set_high_limit", full.SetHighLimit(80.0) == nil)
	v, err := full.GetHighLimit()
	check("high_limit_roundtrip", err == nil && v == 80.0)
	check("set_low_limit", full.SetLowLimit(-10.25) == nil)
	v, err = full.GetLowLimit()
	check("low_limit_roundtrip", err == nil && v == -10.25)
	check("set_offset", full.SetTemperatureOffset(0.5) == nil)
	v, err = full.GetTemperatureOffset()
	check("offset_roundtrip", err == nil && v == 0.5)
	check("clear_offset", full.SetTemperatureOffset(0.0) == nil)

	// The EEPROM is never unlocked here, so no power-on default changes.
	busy, err := full.IsEepromBusy()
	check("eeprom_not_busy", err == nil && !busy)
	check("write_eeprom2", full.WriteEepromScratch(2, 0xA55A) == nil)
	e2, err := full.ReadEepromScratch(2)
	check("eeprom2_volatile_roundtrip", err == nil && e2 == 0xA55A)

	// High limit below ambient forces HIGH_Alert on the next conversion; in
	// Alert mode the flag latches until CONFIGURATION is read.
	check("configure_alert", full.ConfigureAlert(temperature.TMP117AlertWindow,
		temperature.TMP117AlertActiveLow, temperature.TMP117PinAlert) == nil)
	check("configure_fastest", full.Configure(temperature.TMP117Continuous, 0, 0.0155) == nil)
	check("set_high_below_ambient", full.SetHighLimit(t-20) == nil)
	time.Sleep(100 * time.Millisecond)
	st, err := full.PollInterrupt()
	check("poll_interrupt_high", err == nil && st&temperature.TMP117SourceHigh != 0)
	check("restore_high_limit", full.SetHighLimit(80.0) == nil)
	time.Sleep(100 * time.Millisecond)
	_, _ = full.PollInterrupt()
	st, err = full.PollInterrupt()
	check("poll_interrupt_clear", err == nil && st&temperature.TMP117SourceHigh == 0)

	// Soft reset reloads CONFIGURATION, the limits and the offset from EEPROM.
	check("reset", full.Reset() == nil)
	cfg, err = full.GetConfig()
	check("reset_restores_config", err == nil && cfg.Mode == temperature.TMP117Continuous)

	println("===DONE:", passed, "passed,", failed, "failed===")
}

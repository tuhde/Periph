//go:build tinygo

// TMP102 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func sleep(ms int) { time.Sleep(time.Duration(ms) * time.Millisecond) }

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, temperature.TMP102I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	s := temperature.NewTMP102Full(conn)
	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			println("PASS", label)
			passed++
		} else {
			println("FAIL", label)
			failed++
		}
	}
	cfgBits := func() uint16 {
		c, err := s.ReadConfig() // Read CONFIGURATION, () → (uint16, error)
		must(err)
		return c
	}

	sleep(50)
	t, err := s.ReadTemperature() // Read temperature, () → (float32 °C, error)
	must(err)
	check("temperature_plausible", t >= -40 && t <= 125)
	raw, err := s.ReadRaw() // Read raw count, () → (int16, error)
	must(err)
	d := float32(raw)*0.0625 - t
	check("raw_matches_temperature", d < 1 && d > -1)
	check("default_config", cfgBits()&0x6000 == 0x6000)

	must(s.SetConversionRate(1)) // Set conversion rate, (hz) → error
	check("rate_roundtrip", (cfgBits()>>6)&3 == 1)
	must(s.SetConversionRate(4))

	must(s.SetTHigh(40)) // Set T_HIGH, (celsius °C) → error
	h, _ := s.GetTHigh()
	check("t_high_roundtrip", h == 40)
	must(s.SetTLow(-10.25)) // Set T_LOW, (celsius °C) → error
	l, _ := s.GetTLow()
	check("t_low_roundtrip", l == -10.25)
	must(s.SetTHigh(80))
	must(s.SetTLow(75))

	must(s.SetFaultQueue(4)) // Set fault queue, (count) → error
	check("fault_queue", (cfgBits()>>11)&3 == 2)
	must(s.SetFaultQueue(1))
	must(s.SetPolarity(true)) // Set ALERT polarity, (activeHigh bool) → error
	check("polarity", cfgBits()&0x0400 != 0)
	must(s.SetPolarity(false))
	must(s.SetThermostatMode(true)) // Set thermostat mode, (interrupt bool) → error
	check("thermostat_mode", cfgBits()&0x0200 != 0)
	must(s.SetThermostatMode(false))

	must(s.SetExtendedMode(true)) // Set extended mode, (on bool) → error
	sleep(300)
	ext, _ := s.ReadTemperature()
	check("extended_mode_reading", ext-t < 3 && t-ext < 3)
	must(s.SetExtendedMode(false))

	must(s.Shutdown()) // Shut down, () → error
	check("shutdown", cfgBits()&0x0100 != 0)
	one, err := s.OneShot() // One-shot reading, () → (float32 °C, error)
	check("one_shot_plausible", err == nil && one >= -40 && one <= 125)
	check("one_shot_stays_shutdown", cfgBits()&0x0100 != 0)
	must(s.Wake()) // Wake, () → error
	check("wake", cfgBits()&0x0100 == 0)

	// T_HIGH below ambient forces an alert after the fault queue elapses.
	must(s.SetThermostatMode(false))
	must(s.SetTLow(t - 21))
	must(s.SetTHigh(t - 20))
	sleep(500)
	a, _ := s.AlertState() // Read alert state, () → (bool, error)
	check("alert_state_active", a)
	p, _ := s.PollInterrupt() // Poll the alert state, () → (bool, error)
	check("poll_interrupt_active", p)
	must(s.SetTHigh(80))
	must(s.SetTLow(75))
	sleep(500)
	a, _ = s.AlertState()
	check("alert_state_clear", !a)

	println("===DONE:", passed, "passed,", failed, "failed===")
	if failed != 0 {
		println("exit")
	}
}

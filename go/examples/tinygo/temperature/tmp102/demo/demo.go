//go:build tinygo

// TMP102 demo example — TinyGo (Raspberry Pi Pico W).
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

// report prints a value in milli-units (TinyGo's println has no float formatting).
func report(label string, v float32) { println(label, "(milli)", int32(v*1000)) }

func sleep(ms int) { time.Sleep(time.Duration(ms) * time.Millisecond) }

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, temperature.TMP102I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	s := temperature.NewTMP102Full(conn) // Create TMP102 Full driver, (conn) → *TMP102Full

	// Thermostat with one-shot sampling: read the power-on state, sample at 4 Hz,
	// switch to 8 Hz and the 13-bit mode, take one-shot readings, then run an
	// interrupt-mode thermostat around the current temperature while the sensor
	// is warmed with a finger. Finally the power-on defaults are restored.
	cfg, err := s.ReadConfig() // Read CONFIGURATION, () → (uint16, error)
	must(err)
	report("config", float32(cfg))
	low, err := s.GetTLow() // Read T_LOW, () → (float32 °C, error)
	must(err)
	high, err := s.GetTHigh() // Read T_HIGH, () → (float32 °C, error)
	must(err)
	report("t_low", low)
	report("t_high", high)

	// --- Continuous reading at 4 Hz for 5 s ---
	for i := 0; i < 20; i++ {
		t, err := s.ReadTemperature() // Read temperature, () → (float32 °C, error)
		must(err)
		report("temperature", t)
		raw, err := s.ReadRaw() // Read raw count, () → (int16, error)
		must(err)
		report("raw", float32(raw))
		sleep(250)
	}

	// --- 8 Hz and the 13-bit extended mode ---
	must(s.SetConversionRate(8))  // Set conversion rate, (hz) → error
	must(s.SetExtendedMode(true)) // Set extended mode, (on bool) → error
	sleep(200)
	t, err := s.ReadTemperature() // Read temperature, () → (float32 °C, error)
	must(err)
	report("extended", t)
	must(s.SetExtendedMode(false)) // Set extended mode, (on bool) → error
	must(s.SetConversionRate(4))   // Set conversion rate, (hz) → error

	// --- Shutdown and one-shot readings (about 10 ms each) ---
	must(s.Shutdown()) // Shut down, () → error
	for i := 0; i < 5; i++ {
		one, err := s.OneShot() // One-shot reading, () → (float32 °C, error)
		must(err)
		report("one-shot", one)
	}
	must(s.Wake()) // Wake, () → error

	// --- Interrupt-mode thermostat around the current temperature ---
	now, err := s.ReadTemperature() // Read temperature, () → (float32 °C, error)
	must(err)
	must(s.SetTHigh(now + 1))       // Set T_HIGH, (celsius °C) → error
	must(s.SetTLow(now - 1))        // Set T_LOW, (celsius °C) → error
	must(s.SetFaultQueue(2))        // Set fault queue, (count) → error
	must(s.SetPolarity(false))      // Set ALERT polarity, (activeHigh bool) → error
	must(s.SetThermostatMode(true)) // Set thermostat mode, (interrupt bool) → error
	println("warm the sensor with a finger ...")
	for i := 0; i < 40; i++ {
		alerting, err := s.PollInterrupt() // Poll the alert state, () → (bool, error)
		must(err)
		if alerting {
			c, err := s.ReadTemperature() // Read temperature, () → (float32 °C, error)
			must(err)
			state, err := s.AlertState() // Read alert state, () → (bool, error)
			must(err)
			report("ALERT", c)
			println("alert_state", state)
		}
		sleep(250)
	}
	must(s.OnInterrupt(func(c float32) { report("ALERT", c) })) // Subscribe to ALERT, (callback func(float32)) → error
	must(s.OffInterrupt())                                      // Unsubscribe, () → error

	// --- Restore the power-on defaults ---
	must(s.SetThermostatMode(false)) // Set thermostat mode, (interrupt bool) → error
	must(s.SetFaultQueue(1))         // Set fault queue, (count) → error
	must(s.SetTHigh(80))             // Set T_HIGH, (celsius °C) → error
	must(s.SetTLow(75))              // Set T_LOW, (celsius °C) → error
	must(s.WriteConfig(0x60A0))      // Write CONFIGURATION, (value uint16) → error
	must(s.Reset())                  // General-call reset, () → error
}

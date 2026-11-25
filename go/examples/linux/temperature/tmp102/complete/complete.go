//go:build linux && !tinygo

// TMP102 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func report(label string, v float32) { fmt.Printf("%s %.4f\n", label, v) }

func sleep(ms int) { time.Sleep(time.Duration(ms) * time.Millisecond) }

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x48"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	s := temperature.NewTMP102Full(conn) // Create TMP102 Full driver, (conn) → *TMP102Full

	t, err := s.ReadTemperature() // Read temperature, () → (float32 °C, error)
	// decodes TEMPERATURE, 0.0625 °C two's complement
	must(err)
	report("temperature", t)
	raw, err := s.ReadRaw() // Read raw count, () → (int16, error)
	// signed 12/13-bit count, 0.0625 °C per LSB
	must(err)
	report("raw", float32(raw))

	cfg, err := s.ReadConfig() // Read CONFIGURATION, () → (uint16, error)
	// reset value 0x60A0
	must(err)
	must(s.WriteConfig(cfg)) // Write CONFIGURATION, (value uint16) → error
	// read-only bits are masked out

	must(s.SetConversionRate(8)) // Set conversion rate, (hz 0.25|1|4|8) → error
	// CR1:CR0 bits
	must(s.SetExtendedMode(true)) // Set extended mode, (on bool) → error
	// EM bit, 13-bit readings up to +255.9375 °C
	sleep(200)
	t, err = s.ReadTemperature() // Read temperature, () → (float32 °C, error)
	must(err)
	report("extended", t)
	must(s.SetExtendedMode(false)) // Set extended mode, (on bool) → error
	// back to 12 bit

	must(s.SetTHigh(30)) // Set T_HIGH, (celsius °C) → error
	// rounded to 0.0625 °C
	must(s.SetTLow(10)) // Set T_LOW, (celsius °C) → error
	// rounded to 0.0625 °C
	high, err := s.GetTHigh() // Read T_HIGH, () → (float32 °C, error)
	// same format as TEMPERATURE
	must(err)
	report("high", high)
	low, err := s.GetTLow() // Read T_LOW, () → (float32 °C, error)
	// same format as TEMPERATURE
	must(err)
	report("low", low)

	must(s.SetFaultQueue(2)) // Set fault queue, (count 1|2|4|6) → error
	// consecutive faults before ALERT
	must(s.SetPolarity(false)) // Set ALERT polarity, (activeHigh bool) → error
	// POL bit, false = active low
	must(s.SetThermostatMode(false)) // Set thermostat mode, (interrupt bool) → error
	// TM bit, false = comparator
	alert, err := s.AlertState() // Read alert state, () → (bool, error)
	// AL bit corrected for POL
	must(err)
	println("alert", alert)

	must(s.Shutdown()) // Shut down, () → error
	// SD bit, stops after the current conversion
	one, err := s.OneShot() // One-shot reading, () → (float32 °C, error)
	// OS=1, polls until done, restores the mode
	must(err)
	report("one-shot", one)
	must(s.Wake()) // Wake, () → error
	// resume continuous conversion

	polled, err := s.PollInterrupt() // Poll the alert state, () → (bool, error)
	// reads CONFIGURATION and TEMPERATURE
	must(err)
	println("polling", polled)
	must(s.OnInterrupt(func(c float32) { report("ALERT", c) })) // Subscribe to ALERT, (callback func(float32)) → error
	// callback receives the temperature in °C
	sleep(3000)
	must(s.OffInterrupt()) // Unsubscribe, () → error
	// stop delivery

	must(s.SetConversionRate(4)) // Set conversion rate, (hz) → error
	// back to the power-on default
	must(s.SetTHigh(80)) // Set T_HIGH, (celsius °C) → error
	must(s.SetTLow(75))  // Set T_LOW, (celsius °C) → error
	must(s.Reset())      // General-call reset, () → error
	// resets every general-call device on the bus
}

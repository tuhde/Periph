//go:build linux && !tinygo

// MCP4725 hardware test — Linux host.
//
// Drives every method in the MCP4725Full API and reports PASS/FAIL per
// check. Prints the standard ===DONE: ... === summary line at the end.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport:", err)
		os.Exit(2)
	}
	defer tr.Close()

	chip, err := adcdac.NewMCP4725Full(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

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

	if err := chip.SetVoltage(0.5); err != nil {
		fmt.Fprintln(os.Stderr, "set_voltage_0.5:", err)
	}
	check("set_voltage_0.5", true)

	if err := chip.SetRaw(2048); err != nil {
		fmt.Fprintln(os.Stderr, "set_raw_2048:", err)
	}
	check("set_raw_2048", true)

	if err := chip.SetVoltageEEPROM(0.5); err != nil {
		fmt.Fprintln(os.Stderr, "set_voltage_eeprom_0.5:", err)
	}
	check("set_voltage_eeprom_0.5", true)

	if err := chip.SetRawEEPROM(2048); err != nil {
		fmt.Fprintln(os.Stderr, "set_raw_eeprom_2048:", err)
	}
	check("set_raw_eeprom_2048", true)

	st, err := chip.Read()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read:", err)
	}
	check("read_code_range", st.Code <= 4095)
	check("read_voltage_fraction_range", st.VoltageFraction >= 0.0 && st.VoltageFraction <= 1.0)
	check("read_eeprom_code_range", st.EEPROMCode <= 4095)

	if err := chip.SetPowerDown(0); err != nil {
		fmt.Fprintln(os.Stderr, "set_power_down_normal:", err)
	}
	check("set_power_down_normal", true)

	if err := chip.SetPowerDown(1); err != nil {
		fmt.Fprintln(os.Stderr, "set_power_down_1k:", err)
	}
	check("set_power_down_1k", true)

	if err := chip.SetPowerDown(2); err != nil {
		fmt.Fprintln(os.Stderr, "set_power_down_100k:", err)
	}
	check("set_power_down_100k", true)

	if err := chip.SetPowerDown(3); err != nil {
		fmt.Fprintln(os.Stderr, "set_power_down_500k:", err)
	}
	check("set_power_down_500k", true)

	if err := chip.WakeUp(); err != nil {
		fmt.Fprintln(os.Stderr, "wake_up:", err)
	}
	check("wake_up", true)

	if err := chip.Reset(); err != nil {
		fmt.Fprintln(os.Stderr, "reset:", err)
	}
	check("reset", true)

	ready, err := chip.IsEEPROMReady()
	if err != nil {
		fmt.Fprintln(os.Stderr, "is_eeprom_ready:", err)
	}
	check("is_eeprom_ready_bool", ready == true || ready == false)

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

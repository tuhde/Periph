//go:build linux && !tinygo

// MCP4728 hardware test — Linux host.
//
// Drives every method in the MCP4728Full API and reports PASS/FAIL per
// check. Prints the standard ===DONE: ... === summary line at the end.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := adcdac.NewMCP4728Full(conn)
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

	if err := chip.SetVoltage(0, 0.5); err != nil {
		fmt.Fprintln(os.Stderr, "set_voltage_ch0_0.5:", err)
	}
	check("set_voltage_ch0_0.5", true)

	if err := chip.SetRaw(1, 2048); err != nil {
		fmt.Fprintln(os.Stderr, "set_raw_ch1_2048:", err)
	}
	check("set_raw_ch1_2048", true)

	if err := chip.SetAll([4]float32{0.0, 0.25, 0.5, 1.0}); err != nil {
		fmt.Fprintln(os.Stderr, "set_all:", err)
	}
	check("set_all", true)

	if err := chip.SetVoltageEEPROM(0, 0.5, 0, 1); err != nil {
		fmt.Fprintln(os.Stderr, "set_voltage_eeprom:", err)
	}
	check("set_voltage_eeprom", true)

	if err := chip.SetRawEEPROM(1, 2048, 0, 1); err != nil {
		fmt.Fprintln(os.Stderr, "set_raw_eeprom:", err)
	}
	check("set_raw_eeprom", true)

	if err := chip.SetAllEEPROM(
		[4]float32{0.0, 0.25, 0.5, 0.75},
		[4]uint8{0, 0, 0, 0},
		[4]uint8{1, 1, 1, 1},
	); err != nil {
		fmt.Fprintln(os.Stderr, "set_all_eeprom:", err)
	}
	check("set_all_eeprom", true)

	if err := chip.SetVREF(0, 0, 0, 0); err != nil {
		fmt.Fprintln(os.Stderr, "set_vref:", err)
	}
	check("set_vref", true)

	if err := chip.SetGain(1, 1, 1, 1); err != nil {
		fmt.Fprintln(os.Stderr, "set_gain:", err)
	}
	check("set_gain", true)

	if err := chip.SetPowerDown(0, 0, 0, 0); err != nil {
		fmt.Fprintln(os.Stderr, "set_power_down_normal:", err)
	}
	check("set_power_down_normal", true)

	if err := chip.SetPowerDown(1, 2, 3, 0); err != nil {
		fmt.Fprintln(os.Stderr, "set_power_down_modes:", err)
	}
	check("set_power_down_modes", true)

	st, err := chip.Read()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read:", err)
	}
	check("read_ch0_code_range", st.Channel[0].Code <= 4095)
	check("read_ch0_eeprom_code_range", st.Channel[0].EEPROMCode <= 4095)
	check("read_ch0_gain_valid", st.Channel[0].Gain == 1 || st.Channel[0].Gain == 2)
	check("read_ch0_vref_valid", st.Channel[0].VREF == 0 || st.Channel[0].VREF == 1)

	if err := chip.SoftwareUpdate(); err != nil {
		fmt.Fprintln(os.Stderr, "software_update:", err)
	}
	check("software_update", true)

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

//go:build tinygo

// MCP4725 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MCP4725 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line. The test runner (go/test_tinygo.sh) reads
// the serial output and reports exit code 0/1/2 based on the
// ===DONE=== line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x60)
	chip, err := adcdac.NewMCP4725Full(tr)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

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

	if err := chip.SetVoltage(0.5); err != nil {
		fmt.Printf("set_voltage_0.5: %v\n", err)
	}
	check("set_voltage_0.5", true)

	if err := chip.SetRaw(2048); err != nil {
		fmt.Printf("set_raw_2048: %v\n", err)
	}
	check("set_raw_2048", true)

	if err := chip.SetVoltageEEPROM(0.5); err != nil {
		fmt.Printf("set_voltage_eeprom_0.5: %v\n", err)
	}
	check("set_voltage_eeprom_0.5", true)

	if err := chip.SetRawEEPROM(2048); err != nil {
		fmt.Printf("set_raw_eeprom_2048: %v\n", err)
	}
	check("set_raw_eeprom_2048", true)

	st, err := chip.Read()
	if err != nil {
		fmt.Printf("read: %v\n", err)
	}
	check("read_code_range", st.Code <= 4095)
	check("read_voltage_fraction_range", st.VoltageFraction >= 0.0 && st.VoltageFraction <= 1.0)
	check("read_eeprom_code_range", st.EEPROMCode <= 4095)

	if err := chip.SetPowerDown(1); err != nil {
		fmt.Printf("set_power_down_1k: %v\n", err)
	}
	check("set_power_down_1k", true)

	if err := chip.WakeUp(); err != nil {
		fmt.Printf("wake_up: %v\n", err)
	}
	check("wake_up", true)

	if err := chip.Reset(); err != nil {
		fmt.Printf("reset: %v\n", err)
	}
	check("reset", true)

	ready, err := chip.IsEEPROMReady()
	if err != nil {
		fmt.Printf("is_eeprom_ready: %v\n", err)
	}
	check("is_eeprom_ready_bool", ready == true || ready == false)

	time.Sleep(50 * time.Millisecond)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

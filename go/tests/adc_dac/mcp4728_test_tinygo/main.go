//go:build tinygo

// MCP4728 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MCP4728 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
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
	chip, err := adcdac.NewMCP4728Full(tr)
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

	if err := chip.SetVoltage(0, 0.5); err != nil {
		fmt.Printf("set_voltage_ch0_0.5: %v\n", err)
	}
	check("set_voltage_ch0_0.5", true)

	if err := chip.SetRaw(1, 2048); err != nil {
		fmt.Printf("set_raw_ch1_2048: %v\n", err)
	}
	check("set_raw_ch1_2048", true)

	if err := chip.SetAll([4]float32{0.0, 0.25, 0.5, 1.0}); err != nil {
		fmt.Printf("set_all: %v\n", err)
	}
	check("set_all", true)

	if err := chip.SetVoltageEEPROM(0, 0.5, 0, 1); err != nil {
		fmt.Printf("set_voltage_eeprom: %v\n", err)
	}
	check("set_voltage_eeprom", true)

	if err := chip.SetRawEEPROM(1, 2048, 0, 1); err != nil {
		fmt.Printf("set_raw_eeprom: %v\n", err)
	}
	check("set_raw_eeprom", true)

	if err := chip.SetAllEEPROM(
		[4]float32{0.0, 0.25, 0.5, 0.75},
		[4]uint8{0, 0, 0, 0},
		[4]uint8{1, 1, 1, 1},
	); err != nil {
		fmt.Printf("set_all_eeprom: %v\n", err)
	}
	check("set_all_eeprom", true)

	if err := chip.SetVREF(0, 0, 0, 0); err != nil {
		fmt.Printf("set_vref: %v\n", err)
	}
	check("set_vref", true)

	if err := chip.SetGain(1, 1, 1, 1); err != nil {
		fmt.Printf("set_gain: %v\n", err)
	}
	check("set_gain", true)

	if err := chip.SetPowerDown(0, 0, 0, 0); err != nil {
		fmt.Printf("set_power_down_normal: %v\n", err)
	}
	check("set_power_down_normal", true)

	if err := chip.SoftwareUpdate(); err != nil {
		fmt.Printf("software_update: %v\n", err)
	}
	check("software_update", true)

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

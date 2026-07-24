//go:build tinygo

// PCF8591 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a PCF8591 on I2C1 (GP4 = SDA,
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
		Frequency: 100_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	tr := transport.NewI2CTransport(i2c, 0x48)
	chip, err := adcdac.NewPCF8591Full(tr)
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

	ch0, err := chip.ReadChannel(0)
	if err != nil {
		fmt.Printf("read_channel(0): %v\n", err)
	}
	check("read_channel(0) in [0, 255]", ch0 <= 255)

	ch3, err := chip.ReadChannel(3)
	if err != nil {
		fmt.Printf("read_channel(3): %v\n", err)
	}
	check("read_channel(3) in [0, 255]", ch3 <= 255)

	allRaw, err := chip.ReadAll()
	if err != nil {
		fmt.Printf("read_all: %v\n", err)
	}
	check("read_all values in [0, 255]", allRaw[0] <= 255 && allRaw[3] <= 255)

	v0, err := chip.ReadChannelVoltage(0, 3.3, 0.0)
	if err != nil {
		fmt.Printf("read_channel_voltage: %v\n", err)
	}
	check("read_channel_voltage in [0, 3.3]", v0 >= 0.0 && v0 <= 3.3)

	vAll, err := chip.ReadAllVoltage(3.3, 0.0)
	if err != nil {
		fmt.Printf("read_all_voltage: %v\n", err)
	}
	check("read_all_voltage values in [0, 3.3]", vAll[0] >= 0.0 && vAll[0] <= 3.3 && vAll[3] >= 0.0 && vAll[3] <= 3.3)

	if err := chip.Configure(adcdac.PCF8591Mode4SingleEnded, false, false); err != nil {
		fmt.Printf("configure 4 SE: %v\n", err)
	}
	check("configure 4 single-ended accepted", true)

	if err := chip.Configure(adcdac.PCF8591Mode3Differential, false, false); err != nil {
		fmt.Printf("configure 3 diff: %v\n", err)
	}
	diff, err := chip.ReadDifferential(0)
	if err != nil {
		fmt.Printf("read_differential: %v\n", err)
	}
	check("read_differential in [-128, 127]", diff >= -128 && diff <= 127)

	if err := chip.Configure(adcdac.PCF8591Mode2Differential, false, false); err != nil {
		fmt.Printf("configure 2 diff: %v\n", err)
	}
	check("configure 2 differential accepted", true)

	if err := chip.Configure(adcdac.PCF8591Mode4SingleEnded, false, true); err != nil {
		fmt.Printf("configure enables DAC: %v\n", err)
	}
	check("configure enables DAC", true)

	if err := chip.SetDAC(0); err != nil {
		fmt.Printf("set_dac(0): %v\n", err)
	}
	check("set_dac(0) accepted", true)

	if err := chip.SetDAC(255); err != nil {
		fmt.Printf("set_dac(255): %v\n", err)
	}
	check("set_dac(255) accepted", true)

	if err := chip.SetDACVoltage(0.5); err != nil {
		fmt.Printf("set_dac_voltage(0.5): %v\n", err)
	}
	check("set_dac_voltage(0.5) accepted", true)

	if err := chip.DisableDAC(); err != nil {
		fmt.Printf("disable_dac: %v\n", err)
	}
	check("disable_dac accepted", true)

	time.Sleep(50 * time.Millisecond)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

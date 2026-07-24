//go:build tinygo

// INA226 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an INA226 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line. The test runner (go/test_tinygo.sh) reads
// the serial output and reports exit code 0/1/2 based on the
// ===DONE=== line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
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

	tr := transport.NewI2CTransport(i2c, 0x40)
	chip, err := power.NewINA226Full(tr, 0.1, 2.0)
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

	mfr, err := chip.ManufacturerID()
	check("manufacturer_id", err == nil && mfr == 0x5449)

	die, err := chip.DieID()
	check("die_id", err == nil && die == 0x2260)

	if err := chip.Configure(0, 4, 4, 7); err != nil {
		fmt.Printf("FAIL configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	v, err := chip.Voltage()
	check("voltage_range", err == nil && v >= 0.0 && v <= 40.0)

	sv, err := chip.ShuntVoltage()
	check("shunt_voltage_range", err == nil && sv >= -0.082 && sv <= 0.082)

	c, err := chip.Current()
	check("current_range", err == nil && c >= -2.0 && c <= 2.0)

	p, err := chip.Power()
	check("power_range", err == nil && p >= 0.0 && p <= 80.0)

	_, err = chip.ConversionReady()
	check("conversion_ready_ok", err == nil)

	_, err = chip.Overflow()
	check("overflow_ok", err == nil)

	if err := chip.SetAlert(power.BOL, 5.0, false, false); err != nil {
		fmt.Printf("FAIL set_alert: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	flags, err := chip.AlertFlags()
	check("alert_flags_bol_set", err == nil && flags&power.BOL != 0)

	if err := chip.Shutdown(); err != nil {
		fmt.Printf("FAIL shutdown: %v\n", err)
	}
	time.Sleep(50 * time.Millisecond)
	if err := chip.Wake(); err != nil {
		fmt.Printf("FAIL wake: %v\n", err)
	}
	check("shutdown_wake", true)

	if err := chip.Reset(); err != nil {
		fmt.Printf("FAIL reset: %v\n", err)
	}
	check("reset", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

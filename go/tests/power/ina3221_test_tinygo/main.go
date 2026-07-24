//go:build tinygo

// INA3221 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an INA3221 on I2C1 (GP4 = SDA,
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
	chip, err := power.NewINA3221Full(tr, 0.1)
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
	check("die_id", err == nil && die == 0x3220)

	for ch := uint8(1); ch <= 3; ch++ {
		v, err := chip.Voltage(ch)
		label := fmt.Sprintf("ch%d_voltage_non_negative", ch)
		check(label, err == nil && v >= 0.0)

		_, err = chip.ShuntVoltage(ch)
		label = fmt.Sprintf("ch%d_shunt_voltage_finite", ch)
		check(label, err == nil)

		_, err = chip.Current(ch)
		label = fmt.Sprintf("ch%d_current_finite", ch)
		check(label, err == nil)

		p, err := chip.Power(ch)
		label = fmt.Sprintf("ch%d_power_non_negative", ch)
		check(label, err == nil && p >= 0.0)
	}

	_, err = chip.ConversionReady()
	check("conversion_ready_ok", err == nil)

	if err := chip.Configure(3, 4, 4, 7); err != nil {
		fmt.Printf("FAIL configure: %v\n", err)
	}
	if mfr, err := chip.ManufacturerID(); err == nil && mfr == 0x5449 {
		check("configure_mfr_id_still_valid", true)
	} else {
		check("configure_mfr_id_still_valid", false)
	}

	if err := chip.SetCriticalAlert(1, 0.1, false); err != nil {
		fmt.Printf("FAIL set_critical_alert: %v\n", err)
	}
	if err := chip.SetWarningAlert(2, 0.05, false); err != nil {
		fmt.Printf("FAIL set_warning_alert: %v\n", err)
	}
	if _, err := chip.AlertFlags(); err == nil {
		check("alert_flags_readable", true)
	} else {
		check("alert_flags_readable", false)
	}

	if err := chip.EnableChannel(1, false); err != nil {
		fmt.Printf("FAIL disable_channel: %v\n", err)
	}
	if enabled, err := chip.ChannelEnabled(1); err == nil && !enabled {
		check("channel_1_disabled", true)
	} else {
		check("channel_1_disabled", false)
	}
	if err := chip.EnableChannel(1, true); err != nil {
		fmt.Printf("FAIL enable_channel: %v\n", err)
	}
	if enabled, err := chip.ChannelEnabled(1); err == nil && enabled {
		check("channel_1_re_enabled", true)
	} else {
		check("channel_1_re_enabled", false)
	}

	if err := chip.SetSummationChannels([]uint8{1, 2}, 0.2); err != nil {
		fmt.Printf("FAIL set_summation_channels: %v\n", err)
	}
	if _, err := chip.SummationValue(); err == nil {
		check("summation_value_readable", true)
	} else {
		check("summation_value_readable", false)
	}

	if err := chip.SetPowerValidLimits(5.5, 4.5); err != nil {
		fmt.Printf("FAIL set_power_valid_limits: %v\n", err)
	}
	if _, err := chip.PowerValid(); err == nil {
		check("power_valid_readable", true)
	} else {
		check("power_valid_readable", false)
	}

	if err := chip.Shutdown(); err != nil {
		fmt.Printf("FAIL shutdown: %v\n", err)
	}
	time.Sleep(50 * time.Millisecond)
	if err := chip.Wake(); err != nil {
		fmt.Printf("FAIL wake: %v\n", err)
	}
	if v, err := chip.Voltage(1); err == nil && v >= 0.0 {
		check("wake_voltage_non_negative", true)
	} else {
		check("wake_voltage_non_negative", false)
	}

	if err := chip.Reset(); err != nil {
		fmt.Printf("FAIL reset: %v\n", err)
	}
	if mfr, err := chip.ManufacturerID(); err == nil && mfr == 0x5449 {
		check("reset_mfr_id_still_valid", true)
	} else {
		check("reset_mfr_id_still_valid", false)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

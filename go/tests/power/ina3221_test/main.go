//go:build linux && !tinygo

// INA3221 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the INA3221 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
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

	chip, err := power.NewINA3221Full(tr, 0.1)
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
		fmt.Fprintln(os.Stderr, "configure:", err)
		os.Exit(2)
	}
	if mfr, err := chip.ManufacturerID(); err == nil && mfr == 0x5449 {
		check("configure_mfr_id_still_valid", true)
	} else {
		check("configure_mfr_id_still_valid", false)
	}

	if err := chip.SetCriticalAlert(1, 0.1, false); err != nil {
		fmt.Fprintln(os.Stderr, "set_critical_alert:", err)
		os.Exit(2)
	}
	if err := chip.SetWarningAlert(2, 0.05, false); err != nil {
		fmt.Fprintln(os.Stderr, "set_warning_alert:", err)
		os.Exit(2)
	}
	if _, err := chip.AlertFlags(); err == nil {
		check("alert_flags_readable", true)
	} else {
		check("alert_flags_readable", false)
	}

	if err := chip.EnableChannel(1, false); err != nil {
		fmt.Fprintln(os.Stderr, "disable_channel:", err)
		os.Exit(2)
	}
	if enabled, err := chip.ChannelEnabled(1); err == nil && !enabled {
		check("channel_1_disabled", true)
	} else {
		check("channel_1_disabled", false)
	}
	if err := chip.EnableChannel(1, true); err != nil {
		fmt.Fprintln(os.Stderr, "enable_channel:", err)
		os.Exit(2)
	}
	if enabled, err := chip.ChannelEnabled(1); err == nil && enabled {
		check("channel_1_re_enabled", true)
	} else {
		check("channel_1_re_enabled", false)
	}

	if err := chip.SetSummationChannels([]uint8{1, 2}, 0.2); err != nil {
		fmt.Fprintln(os.Stderr, "set_summation_channels:", err)
		os.Exit(2)
	}
	if _, err := chip.SummationValue(); err == nil {
		check("summation_value_readable", true)
	} else {
		check("summation_value_readable", false)
	}

	if err := chip.SetPowerValidLimits(5.5, 4.5); err != nil {
		fmt.Fprintln(os.Stderr, "set_power_valid_limits:", err)
		os.Exit(2)
	}
	if _, err := chip.PowerValid(); err == nil {
		check("power_valid_readable", true)
	} else {
		check("power_valid_readable", false)
	}

	if err := chip.Shutdown(); err != nil {
		fmt.Fprintln(os.Stderr, "shutdown:", err)
		os.Exit(2)
	}
	if err := chip.Wake(); err != nil {
		fmt.Fprintln(os.Stderr, "wake:", err)
		os.Exit(2)
	}
	if v, err := chip.Voltage(1); err == nil && v >= 0.0 {
		check("wake_voltage_non_negative", true)
	} else {
		check("wake_voltage_non_negative", false)
	}

	if err := chip.Reset(); err != nil {
		fmt.Fprintln(os.Stderr, "reset:", err)
		os.Exit(2)
	}
	if mfr, err := chip.ManufacturerID(); err == nil && mfr == 0x5449 {
		check("reset_mfr_id_still_valid", true)
	} else {
		check("reset_mfr_id_still_valid", false)
	}

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

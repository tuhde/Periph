//go:build linux && !tinygo

// INA226 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the INA226 check sequence. Prints
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

	chip, err := power.NewINA226Full(tr, 0.1, 2.0)
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
	check("die_id", err == nil && die == 0x2260)

	if err := chip.Configure(0, 4, 4, 7); err != nil {
		fmt.Fprintln(os.Stderr, "configure:", err)
		os.Exit(2)
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
		fmt.Fprintln(os.Stderr, "set_alert:", err)
		os.Exit(2)
	}
	flags, err := chip.AlertFlags()
	check("alert_flags_bol_set", err == nil && flags&power.BOL != 0)

	if err := chip.Shutdown(); err != nil {
		fmt.Fprintln(os.Stderr, "shutdown:", err)
		os.Exit(2)
	}
	if err := chip.Wake(); err != nil {
		fmt.Fprintln(os.Stderr, "wake:", err)
		os.Exit(2)
	}
	check("shutdown_wake", true)

	if err := chip.Reset(); err != nil {
		fmt.Fprintln(os.Stderr, "reset:", err)
		os.Exit(2)
	}
	check("reset", true)

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

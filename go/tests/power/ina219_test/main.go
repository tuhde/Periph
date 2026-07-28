//go:build linux && !tinygo

// INA219 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the INA219 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := power.NewINA219Full(conn, 0.1, 2.0)
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

	v, err := chip.Voltage()
	check("voltage_range", err == nil && v >= 0.0 && v <= 40.0)

	sv, err := chip.ShuntVoltage()
	check("shunt_voltage_range", err == nil && sv >= -0.320 && sv <= 0.320)

	c, err := chip.Current()
	check("current_range", err == nil && c >= -2.0 && c <= 2.0)

	p, err := chip.Power()
	check("power_range", err == nil && p >= 0.0 && p <= 80.0)

	_, err = chip.ConversionReady()
	check("conversion_ready_ok", err == nil)

	_, err = chip.Overflow()
	check("overflow_ok", err == nil)

	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_BUS_CONT); err != nil {
		fmt.Fprintln(os.Stderr, "configure:", err)
		os.Exit(2)
	}
	if _, err := chip.Voltage(); err == nil {
		check("after_configure_voltage_ok", true)
	} else {
		check("after_configure_voltage_ok", false)
	}

	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_TRIG); err != nil {
		fmt.Fprintln(os.Stderr, "configure_trigger:", err)
		os.Exit(2)
	}
	if err := chip.Trigger(); err != nil {
		fmt.Fprintln(os.Stderr, "trigger:", err)
		os.Exit(2)
	}
	check("trigger_ok", true)

	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_BUS_CONT); err != nil {
		fmt.Fprintln(os.Stderr, "configure_back:", err)
		os.Exit(2)
	}
	if err := chip.Shutdown(); err != nil {
		fmt.Fprintln(os.Stderr, "shutdown:", err)
		os.Exit(2)
	}
	if err := chip.Wake(); err != nil {
		fmt.Fprintln(os.Stderr, "wake:", err)
		os.Exit(2)
	}
	if _, err := chip.Voltage(); err == nil {
		check("shutdown_wake_voltage_ok", true)
	} else {
		check("shutdown_wake_voltage_ok", false)
	}

	if err := chip.Reset(); err != nil {
		fmt.Fprintln(os.Stderr, "reset:", err)
		os.Exit(2)
	}
	if _, err := chip.Voltage(); err == nil {
		check("reset_voltage_ok", true)
	} else {
		check("reset_voltage_ok", false)
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

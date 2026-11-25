//go:build linux && !tinygo

// Max17048 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the Max17048 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x36"), 0, 8)
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

	chip, err := power.NewMax17048Full(conn, 1)
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

	check("voltage_range", func() bool { v, err := chip.Voltage(); return err == nil && v >= 2.5 && v <= 4.6 }())
	check("percent_range", func() bool { v, err := chip.Percent(); return err == nil && v >= 0 && v <= 100 }())
	check("version", func() bool { v, err := chip.Version(); return err == nil && v&0xFFF0 == 0x0010 }())
	check("raw_voltage", func() bool { v, err := chip.RawVoltage(); return err == nil && v>>4 > 2000 }())
	check("charge_rate", func() bool { v, err := chip.ChargeRate(); return err == nil && v > -1000 && v < 1000 }())
	check("rcomp", chip.SetRCOMP(0x97) == nil && func() bool { v, err := chip.GetRCOMP(); return err == nil && v == 0x97 }())
	check("compensate_20c", chip.CompensateTemperature(20) == nil && func() bool { v, err := chip.GetRCOMP(); return err == nil && v == 0x97 }())
	check("alert_soc", chip.SetAlertSOC(10) == nil && func() bool { v, err := chip.GetAlertSOC(); return err == nil && v == 10 }())
	_ = chip.SetAlertSOC(4)
	check("voltage_alert", chip.SetVoltageAlert(3.0, 4.2) == nil && func() bool {
		lo, hi, err := chip.GetVoltageAlert()
		return err == nil && lo > 2.999 && lo < 3.001 && hi > 4.199 && hi < 4.201
	}())
	_ = chip.SetVoltageAlert(0, 5.1)
	check("reset_voltage", chip.SetResetVoltage(2.8) == nil && func() bool { v, err := chip.GetResetVoltage(); return err == nil && v > 2.799 && v < 2.801 }())
	_ = chip.SetResetVoltage(3.0)
	check("hibernate_thresholds", chip.SetHibernateThresholds(26.624, 0.06) == nil && func() bool {
		h, a, err := chip.GetHibernateThresholds()
		return err == nil && h > 26.62 && h < 26.63 && a > 0.059 && a < 0.061
	}())
	check("status_cleared", chip.ClearStatus(0x3F00) == nil && func() bool { v, err := chip.ReadStatus(); return err == nil && v&0x3F00 == 0 }())
	check("poll_interrupt", func() bool { v, err := chip.PollInterrupt(); return err == nil && v == 0 }())
	check("config", func() bool {
		c, err := chip.ReadConfig()
		return err == nil && chip.WriteConfig(c) == nil && c>>8 == 0x97
	}())
	check("sleep", chip.EnableSleep(true) == nil && chip.Sleep() == nil)
	time.Sleep(10 * time.Millisecond)
	check("wake", chip.Wake() == nil && chip.EnableSleep(false) == nil)
	check("voltage_after_wake", func() bool { v, err := chip.Voltage(); return err == nil && v > 0 }())

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

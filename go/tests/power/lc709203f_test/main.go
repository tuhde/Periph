//go:build linux && !tinygo

// LC709203F hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the LC709203F check sequence. Prints
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x0B"), 0, 8)
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

	chip, err := power.NewLC709203FFull(conn, 1000, 1)
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
	check("temperature_range", func() bool { v, err := chip.Temperature(); return err == nil && v > 0 && v < 60 }())
	check("rsoc_range", func() bool { v, err := chip.RSOC(); return err == nil && v <= 100 }())
	check("profile_id", func() bool { v, err := chip.ProfileID(); return err == nil && v != 0 }())
	check("get_profile", func() bool { v, err := chip.GetProfile(); return err == nil && v == 1 }())
	check("set_temperature", chip.SetTemperature(30.0) == nil && func() bool { v, err := chip.Temperature(); return err == nil && v > 29.8 && v < 30.2 }())
	check("apa", chip.SetAPA(0x19) == nil && func() bool { v, err := chip.GetAPA(); return err == nil && v == 0x19 }())
	check("apt", chip.SetAPT(0x1E) == nil && func() bool { v, err := chip.GetAPT(); return err == nil && v == 0x1E }())
	check("alarm_rsoc", chip.SetAlarmRSOC(20) == nil && func() bool { v, err := chip.GetAlarmRSOC(); return err == nil && v == 20 }())
	check("alarm_voltage", chip.SetAlarmVoltage(3.5) == nil && func() bool { v, err := chip.GetAlarmVoltage(); return err == nil && v > 3.499 && v < 3.501 }())
	check("poll_interrupt", func() bool { v, err := chip.PollInterrupt(); return err == nil && v <= 3 }())
	check("init_rsoc", chip.InitRSOC() == nil && func() bool { v, err := chip.RSOC(); return err == nil && v <= 100 }())
	check("sleep", chip.Sleep() == nil && func() bool { v, err := chip.IsSleeping(); return err == nil && v }())
	check("wake", chip.Wake() == nil && func() bool { v, err := chip.IsSleeping(); return err == nil && !v }())
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

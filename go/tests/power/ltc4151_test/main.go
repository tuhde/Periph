//go:build linux && !tinygo

// LTC4151 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the LTC4151 check sequence. Prints
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x6A"), 0, 8)
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

	chip, err := power.NewLTC4151Full(conn, 0.02)
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
	check("voltage_range", err == nil && v >= 7.0 && v <= 80.0)
	c, err := chip.Current()
	check("current_range", err == nil && c >= 0.0 && c <= 4.096)
	p, err := chip.Power()
	check("power_range", err == nil && p >= 0.0 && p <= 330.0)
	sv, err := chip.SenseVoltage()
	check("sense_voltage_range", err == nil && sv >= 0.0 && sv <= 0.08192)
	av, err := chip.AdinVoltage()
	check("adin_range", err == nil && av >= 0.0 && av <= 2.048)
	r, err := chip.ReadAll()
	check("read_all_vin", err == nil && r.Vin >= 7.0 && r.Vin <= 80.0)
	raw, err := chip.ReadRaw(power.LTC4151Vin)
	check("read_raw_vin", err == nil && raw > 0 && raw < 4096)
	ctrl, err := chip.ReadControl()
	check("control_default", err == nil && ctrl == 0x0C)
	m, err := chip.IsSnapshotMode()
	check("not_snapshot_mode", err == nil && !m)
	s, err := chip.Snapshot(power.LTC4151Vin)
	check("snapshot_vin", err == nil && s >= 7.0 && s <= 80.0)
	m, err = chip.IsSnapshotMode()
	check("back_in_continuous", err == nil && !m)
	_ = chip.SetStuckBusTimer(false)
	ctrl, err = chip.ReadControl()
	check("stuck_bus_timer_off", err == nil && ctrl == 0x08)
	_ = chip.SetStuckBusTimer(true)
	ctrl, err = chip.ReadControl()
	check("stuck_bus_timer_on", err == nil && ctrl == 0x0C)

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

//go:build tinygo

// LTC4151 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an LTC4151 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line. The test runner (go/test_tinygo.sh) reads
// the serial output and reports exit code 0/1/2 based on the
// ===DONE=== line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn := connection.NewI2CConnection(i2c, 0x6A, nil, nil)
	chip, err := power.NewLTC4151Full(conn, 0.02)
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
}

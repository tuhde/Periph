//go:build tinygo

// LC709203F hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an LC709203F on I2C1 (GP4 = SDA,
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
		Frequency: 100_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x0B, nil, nil)
	chip, err := power.NewLC709203FFull(conn, 1000, 1)
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
}

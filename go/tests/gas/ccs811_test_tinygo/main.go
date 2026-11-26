//go:build tinygo

// CCS811 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an CCS811 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
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
	conn := connection.NewI2CConnection(i2c, 0x5A, nil, nil)
	chip, _ := gas.NewCCS811Full(conn)

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

	check("init", chip.Init() == nil)
	hwID, hwVersion, err := chip.HardwareVersion()
	check("hardware_id", err == nil && hwID == 0x81)
	check("hardware_version", hwVersion&0xF0 == 0x10)
	_, app, err := chip.FirmwareVersion()
	check("firmware_version", err == nil && app.Major >= 1)
	st, err := chip.Status()
	check("status_app_mode", err == nil && st&gas.CCS811StatusFWMode != 0)
	check("status_no_error", st&gas.CCS811StatusError == 0)
	m, err := chip.Read()
	check("read_eco2_range", err == nil && m.ECO2ppm >= 400 && m.ECO2ppm <= 8192)
	check("read_tvoc_range", m.TVOCppb <= 1187)
	eco2, err := chip.ReadECO2()
	check("read_eco2_single", err == nil && eco2 >= 400 && eco2 <= 8192)
	tvoc, err := chip.ReadTVOC()
	check("read_tvoc_single", err == nil && tvoc <= 1187)
	cur, volt, err := chip.ReadRaw()
	check("read_raw_current", err == nil && cur <= 63)
	check("read_raw_voltage", volt >= 0 && volt <= 1.65)
	ready, err := chip.DataReady()
	check("data_ready_cleared", err == nil && !ready)
	check("set_environment", chip.SetEnvironment(50, 25) == nil)
	check("set_thresholds", chip.SetThresholds(1000, 2000, 50) == nil)
	_, _, err = chip.ReadNTC()
	check("read_ntc", err == nil)
	baseline, err := chip.GetBaseline()
	check("baseline_roundtrip", err == nil && chip.SetBaseline(baseline) == nil)
	e, err := chip.Error()
	check("no_error", err == nil && e == 0)
	_ = chip.EnableInterrupt(gas.CCS811SourceDataReady)
	time.Sleep(1200 * time.Millisecond)
	p, err := chip.PollInterrupt()
	check("poll_interrupt_data_ready", err == nil && p&gas.CCS811StatusDataReady != 0)
	_, _ = chip.Read()
	_ = chip.DisableInterrupt(gas.CCS811SourceDataReady)
	check("set_mode_4", chip.SetMode(4) == nil)
	time.Sleep(600 * time.Millisecond)
	cur, _, err = chip.ReadRaw()
	check("mode_4_raw", err == nil && cur <= 63)
	_ = chip.SetMode(0)
	_ = chip.SetMode(1)
	check("soft_reset", chip.SoftReset() == nil)
	st, err = chip.Status()
	check("status_after_reset", err == nil && st&gas.CCS811StatusFWMode != 0)
	m, err = chip.Read()
	check("read_after_reset", err == nil && m.ECO2ppm >= 400 && m.ECO2ppm <= 8192)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

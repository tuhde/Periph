//go:build tinygo

// AHT21 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an AHT21 on the default I2C0 pins
// (GP4 = SDA, GP5 = SCL). Prints PASS/FAIL per check and ends with the
// standard ===DONE: ... === line. The test runner (go/test_tinygo.sh)
// reads the serial output and reports exit code 0/1/2 based on the
// ===DONE=== line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C0
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	tr := transport.NewI2CTransport(i2c, 0x38)
	chip, err := environmental.NewAHT21Full(tr)
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

	cal, err := chip.IsCalibrated()
	check("is_calibrated", err == nil && cal)

	busy, err := chip.IsBusy()
	check("not_busy_at_idle", err == nil && !busy)

	t, h, err := chip.Read()
	check("read_temperature_range", err == nil && t >= -40 && t <= 120)
	check("read_humidity_range", err == nil && h >= 0 && h <= 100)

	tt, err := chip.ReadTemperature()
	check("read_temperature_only", err == nil && tt >= -40 && tt <= 120)

	hh, err := chip.ReadHumidity()
	check("read_humidity_only", err == nil && hh >= 0 && hh <= 100)

	tc, hc, crcOk, err := chip.ReadWithCrc()
	check("crc_ok", err == nil && crcOk)
	check("crc_temperature_range", err == nil && tc >= -40 && tc <= 120)
	check("crc_humidity_range", err == nil && hc >= 0 && hc <= 100)

	if err := chip.SoftReset(); err != nil {
		fmt.Printf("soft_reset: %v\n", err)
	}
	time.Sleep(50 * time.Millisecond)
	cal, err = chip.IsCalibrated()
	check("calibrated_after_reset", err == nil && cal)

	t2, h2, err := chip.Read()
	check("read_after_reset_temperature", err == nil && t2 >= -40 && t2 <= 120)
	check("read_after_reset_humidity", err == nil && h2 >= 0 && h2 <= 100)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

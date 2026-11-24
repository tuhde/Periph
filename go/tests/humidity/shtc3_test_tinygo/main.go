//go:build tinygo

// SHTC3 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an SHTC3 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
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
	conn := connection.NewI2CConnection(i2c, 0x70, nil, nil)
	chip := humidity.NewSHTC3Full(conn)

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
	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("read_temperature_range", t > -45 && t < 130)
	check("read_humidity_range", rh >= 0 && rh <= 100)
	h, err := chip.ReadHumidity()
	check("read_humidity_single", err == nil && h >= 0 && h <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature_single", err == nil && tc > -45 && tc < 130)
	_, _, err = chip.ReadRaw()
	check("read_raw", err == nil)
	dew, err := chip.DewPoint()
	check("dew_point_below_temperature", err == nil && dew <= tc+1)
	id, err := chip.ReadID()
	check("read_id_product_code", err == nil && id&0x083F == 0x0807)
	chip.SetLowPower(true)
	_, r, err := chip.Read()
	check("low_power_read", err == nil && r >= 0 && r <= 100)
	chip.SetLowPower(false)
	chip.SetSleepBetween(false)
	_, r, err = chip.Read()
	check("read_without_sleep", err == nil && r >= 0 && r <= 100)
	_, r, err = chip.Read()
	check("read_without_sleep_again", err == nil && r >= 0 && r <= 100)
	check("sleep", chip.Sleep() == nil)
	check("wake", chip.Wake() == nil)
	chip.SetSleepBetween(true)
	chip.SetClockStretching(true)
	_, r, err = chip.Read()
	check("read_with_clock_stretching", err == nil && r >= 0 && r <= 100)
	chip.SetClockStretching(false)
	check("soft_reset", chip.SoftReset() == nil)
	_, r, err = chip.Read()
	check("read_after_reset", err == nil && r >= 0 && r <= 100)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

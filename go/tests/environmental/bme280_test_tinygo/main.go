//go:build tinygo

// BME280 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a BME280 on I2C1 (GP4 = SDA, GP5 = SCL).
// Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line. The test runner (go/test_tinygo.sh) reads
// the serial output and reports exit code 0/1/2 based on the
// ===DONE=== line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
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

	conn := connection.NewI2CConnection(i2c, 0x76, nil, nil)
	chip, err := environmental.NewBME280Full(conn)
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

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)

	p, err := chip.Pressure()
	check("pressure_range", err == nil && p >= 300.0 && p <= 1100.0)

	h, err := chip.Humidity()
	check("humidity_range", err == nil && h >= 0.0 && h <= 100.0)

	if err := chip.SetOversampling(
		environmental.BME280OSRSX4,
		environmental.BME280OSRSX2,
		environmental.BME280OSRSX1,
	); err == nil {
		check("set_oversampling", true)
	} else {
		check("set_oversampling", false)
	}

	alt, err := chip.Altitude(1013.25)
	check("altitude_range", err == nil && alt >= -500.0 && alt <= 9000.0)

	dp, err := chip.DewPoint()
	check("dew_point_range", err == nil && dp >= -100.0 && dp <= 100.0)

	cid, err := chip.ChipID()
	check("chip_id", err == nil && cid == 0x60)

	if err := chip.Reset(); err == nil {
		check("reset", true)
	} else {
		check("reset", false)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

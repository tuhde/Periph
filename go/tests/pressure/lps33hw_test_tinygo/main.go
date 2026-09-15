//go:build tinygo

// LPS33HW hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an LPS33HW on I2C1 (GP4 = SDA, GP5 = SCL).
// Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil)
	chip, err := pressure.NewLPS33HWFull(conn, 0x5C)
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

	if err := chip.Configure(
		pressure.LPS33HWODR10Hz, 1, 1, pressure.LPS33HWLPFPBWODR20, 0, 0,
	); err == nil {
		check("configure", true)
	} else {
		check("configure", false)
	}

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)

	p, err := chip.Pressure()
	check("pressure_range", err == nil && p >= 26000.0 && p <= 126000.0)

	pOs, tOs, err := chip.OneShot()
	check("one_shot_pressure", err == nil && pOs >= 26000.0 && pOs <= 126000.0)
	check("one_shot_temperature", err == nil && tOs >= -40.0 && tOs <= 85.0)

	if _, err := chip.Status(); err == nil {
		check("status", true)
	} else {
		check("status", false)
	}

	if err := chip.Reset(); err == nil {
		check("reset", true)
	} else {
		check("reset", false)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
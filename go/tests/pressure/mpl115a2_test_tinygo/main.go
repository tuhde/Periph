//go:build tinygo

// MPL115A2 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MPL115A2 on I2C1 (GP4 = SDA, GP5 = SCL).
// Prints PASS/FAIL per check and ends with the standard ===DONE: ... === line.
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

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil)
	chip, err := pressure.NewMpl115a2Full(conn)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
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

	p, err := chip.ReadPressure()
	check("pressure_range", err == nil && p >= 50000 && p <= 115000)
	t, err := chip.ReadTemperature()
	check("temperature_range", err == nil && t >= -40 && t <= 105)
	padc, tadc, err := chip.ReadRaw()
	check("raw_range", err == nil && padc <= 1023 && tadc <= 1023)
	c := chip.Compensate(padc, tadc)
	check("compensate_range", c >= 50000 && c <= 115000)
	pa, tc, err := chip.Read()
	check("read_both", err == nil && pa >= 50000 && pa <= 115000 && tc >= -40 && tc <= 105)
	hpa, err := chip.PressureHpa()
	check("pressure_hpa", err == nil && hpa >= 500 && hpa <= 1150)
	alt, err := chip.Altitude(101325.0)
	check("altitude", err == nil && alt >= -1000 && alt <= 6000)
	a0, _, _, _ := chip.Coefficients()
	check("coefficients", chip.ReloadCoefficients() == nil && a0 != 0)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

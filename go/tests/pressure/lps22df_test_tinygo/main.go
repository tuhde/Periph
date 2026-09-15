//go:build tinygo

// LPS22DF hardware test — TinyGo / Raspberry Pi Pico W.
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
	chip, err := pressure.NewLPS22DFFull(conn, false)
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
	check("pressure_range", err == nil && p >= 26000.0 && p <= 126000.0)

	if err := chip.Configure(3, 0, true, 1, true); err == nil {
		check("configure", true)
	} else {
		check("configure", false)
	}

	alt, err := chip.Altitude(101325.0)
	check("altitude_range", err == nil && alt >= -500.0 && alt <= 10000.0)

	who, err := chip.WhoAmI()
	check("who_am_i", err == nil && who == 0xB4)

	if err := chip.SoftwareReset(); err == nil {
		check("software_reset", true)
	} else {
		check("software_reset", false)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
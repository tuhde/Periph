//go:build tinygo

package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{
		Frequency: 400 * machine.KHz,
		SDA:       machine.GPIO0,
		SCL:       machine.GPIO1,
	})

	tr, err := connection.NewI2CConnection(machine.I2C0, 0x76, nil, nil)
	if err != nil {
		fmt.Printf("connection: %v\n", err)
		panic(err)
	}
	defer tr.Close()

	chip, err := pressure.NewBMP384Full(tr)
	if err != nil {
		fmt.Printf("new: %v\n", err)
		panic(err)
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

	check("default_osr_p_4", chip.OsrP == 4)

	if t, err := chip.Temperature(); err == nil && t >= -40 && t <= 85 {
		check("temperature_in_range", true)
	} else {
		check("temperature_in_range", false)
	}
	if p, err := chip.Pressure(); err == nil && p >= 300 && p <= 1250 {
		check("pressure_in_range", true)
	} else {
		check("pressure_in_range", false)
	}
	if err := chip.Configure(2, 1, 1, 0x04); err == nil {
		check("configure_ok",
			chip.OsrP == 2 && chip.Iir == 1 && chip.Odr == 0x04)
	} else {
		check("configure_ok", false)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

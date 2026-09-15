//go:build tinygo

// LPS28DFW hardware test — TinyGo / Raspberry Pi Pico W.
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
		fmt.Println("FAIL i2c_configure")
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil)

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

	chip, err := pressure.NewLPS28DFWFull(conn)
	if err != nil {
		fmt.Println("FAIL init")
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	cid, err := chip.ChipID()
	check("chip_id_or_no_chip", err == nil && (cid == 0xB4 || cid == 0x00))

	t, err := chip.ReadTemperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)
	p, err := chip.ReadPressure()
	check("pressure_range", err == nil && p >= 260.0 && p <= 1260.0)

	alt, err := chip.Altitude(1013.25)
	check("altitude_range", err == nil && alt >= -500.0 && alt <= 9000.0)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
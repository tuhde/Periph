//go:build tinygo

// BMP581 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Uses machine.I2C1 (GP4=SDA, GP5=SCL). Prints PASS/FAIL per check and
// ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

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
		println("FAIL i2c_configure:", err.Error())
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x46, nil, nil)

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

	chip, err := pressure.NewBMP581Minimal(conn, false)
	if err != nil {
		println("FAIL init:", err.Error())
		return
	}

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)

	p, err := chip.Pressure()
	check("pressure_range", err == nil && p >= 30000.0 && p <= 125000.0)

	cid, err := chip.ChipID()
	check("chip_id", err == nil && cid == 0x50)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		for {
			time.Sleep(time.Hour)
		}
	}
	for {
		time.Sleep(time.Hour)
	}
}
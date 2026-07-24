//go:build tinygo

// AS5600 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an AS5600 on I2C1 (GP4 = SDA, GP5 = SCL).
// Prints PASS/FAIL per check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x36)
	chip, err := magnetometer.NewAs5600Full(tr)
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

	md, err := chip.IsMagnetDetected()
	check("magnet_detected", err == nil && md)

	a, err := chip.Angle()
	check("angle_range", err == nil && a >= 0.0 && a < 360.0)
	r, err := chip.AngleRaw()
	check("angle_raw_range", err == nil && r <= 4095)
	_, err = chip.AGC()
	check("agc_valid", err == nil)
	bc, err := chip.BurnCount()
	check("burn_count_range", err == nil && bc <= 3)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

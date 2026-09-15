//go:build tinygo

// L3G4200D hardware test — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
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

	conn := connection.NewI2CConnection(i2c, 0x68, nil, nil)

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

	chip, err := gyroscope.NewL3G4200DMinimal(conn, false)
	if err != nil {
		println("FAIL init:", err.Error())
		return
	}

	x, y, z, err := chip.AngularRate()
	check("angular_rate_x_range", err == nil && x >= -50.0 && x <= 50.0)
	check("angular_rate_y_range", err == nil && y >= -50.0 && y <= 50.0)
	check("angular_rate_z_range", err == nil && z >= -50.0 && z <= 50.0)

	cid, err := chip.WHOAMI()
	check("who_am_i", err == nil && cid == 0xD3)

	if err := chip.Configure(gyroscope.L3G4200DODR200Hz, 0, gyroscope.L3G4200DFS500DPS); err == nil {
		check("configure", true)
	} else {
		check("configure", false)
	}

	tmp, err := chip.Temperature()
	check("temperature_range", err == nil && tmp >= -50 && tmp <= 100)

	if err := chip.SetFullScale(gyroscope.L3G4200DFS2000DPS); err == nil {
		check("set_full_scale_2000", true)
	} else {
		check("set_full_scale_2000", false)
	}

	if err := chip.EnableFIFO(gyroscope.L3G4200DFIFOStream, 10); err == nil {
		check("enable_fifo", true)
	} else {
		check("enable_fifo", false)
	}

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

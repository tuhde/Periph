//go:build tinygo

// HDC1008 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a HDC1008 on I2C1 (GP4 = SDA,
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
	conn := connection.NewI2CConnection(i2c, 0x40, nil, nil)
	chip := humidity.NewHDC1008Full(conn)

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
	cfg, err := chip.ReadConfig()
	check("config after init", err == nil && cfg&0xFF00 == 0x1000)

	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("temperature range", t > -40 && t < 125)
	check("humidity range", rh >= 0 && rh <= 100)
	rhp, err := chip.ReadHumidity()
	check("read_humidity range", err == nil && rhp >= 0 && rhp <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature range", err == nil && tc > -40 && tc < 125)
	_, _, err = chip.ReadRaw()
	check("raw ok", err == nil)

	for _, r := range [][2]int{{14, 14}, {11, 11}, {11, 8}, {14, 8}} {
		check("set_resolution", chip.SetResolution(r[0], r[1]) == nil)
		_, _, err = chip.Read()
		check("read at resolution", err == nil)
	}
	check("set_resolution default", chip.SetResolution(14, 14) == nil)

	check("heater on", chip.SetHeater(true) == nil)
	on, err := chip.IsHeaterOn()
	check("heater on readback", err == nil && on)
	check("heater off", chip.SetHeater(false) == nil)
	on, err = chip.IsHeaterOn()
	check("heater off readback", err == nil && !on)
	low, err := chip.BatteryLow()
	check("battery ok", err == nil && !low)
	serial, err := chip.SerialID()
	check("serial id nonzero", err == nil && serial != 0)
	check("conversion time", chip.ConversionTime() > 0.005 && chip.ConversionTime() < 0.02)
	check("software_reset", chip.SoftwareReset() == nil)
	_, _, err = chip.Read()
	check("read after software reset", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

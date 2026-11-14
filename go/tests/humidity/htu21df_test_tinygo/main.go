//go:build tinygo

// HTU21DF hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a HTU21DF on I2C1 (GP4 = SDA,
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
	chip := humidity.NewHTU21DFFull(conn)

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
	reg, err := chip.ReadUserRegister()
	check("user register after reset", err == nil && reg&0x83 == 0x02)

	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("temperature range", t > -40 && t < 125)
	check("humidity range", rh >= -6 && rh <= 119)
	rhp, err := chip.ReadHumidity()
	check("read_humidity range", err == nil && rhp >= -6 && rhp <= 119)
	tc, err := chip.ReadTemperature()
	check("read_temperature range", err == nil && tc > -40 && tc < 125)
	comp, err := chip.ReadHumidityCompensated()
	check("compensated humidity range", err == nil && comp >= -6 && comp <= 125)
	_, _, err = chip.ReadRaw()
	check("raw ok", err == nil)

	for res := 0; res < 4; res++ {
		check("set_resolution", chip.SetResolution(res) == nil)
		got, err := chip.GetResolution()
		check("get_resolution", err == nil && got == res)
		_, _, err = chip.Read()
		check("read at resolution", err == nil)
	}
	check("set_resolution default", chip.SetResolution(0) == nil)

	check("heater on", chip.SetHeater(true) == nil)
	on, err := chip.Heater()
	check("heater on readback", err == nil && on)
	check("heater off", chip.SetHeater(false) == nil)
	on, err = chip.Heater()
	check("heater off readback", err == nil && !on)
	low, err := chip.BatteryLow()
	check("battery ok", err == nil && !low)

	dp, err := chip.DewPoint()
	check("dew point plausible", err == nil && dp > -60 && dp < 60)
	check("soft_reset", chip.SoftReset() == nil)
	_, _, err = chip.Read()
	check("read after soft reset", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

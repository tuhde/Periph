//go:build tinygo

// Si7021 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a Si7021 on I2C1 (GP4 = SDA,
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
	chip := humidity.NewSi7021Full(conn)

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

	check("software_reset", chip.SoftReset() == nil)
	dt, err := chip.DeviceType()
	check("device type", err == nil && dt == 0x15)
	sn, err := chip.SerialNumber()
	check("serial number", err == nil && sn != 0)
	reg, err := chip.ReadUserRegister()
	check("user register after reset", err == nil && reg&0xBF == 0x3A)

	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("temperature range", t > -40 && t < 125)
	check("humidity range", rh >= 0 && rh <= 100)
	rhp, err := chip.ReadHumidity()
	check("read_humidity range", err == nil && rhp >= 0 && rhp <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature range", err == nil && tc > -40 && tc < 125)
	tp, err := chip.ReadTemperaturePrevious()
	check("read_temperature_previous range", err == nil && tp > -40 && tp < 125)
	_, err = chip.ReadRawHumidity()
	check("raw humidity ok", err == nil)
	_, err = chip.ReadRawTemperature()
	check("raw temperature ok", err == nil)

	for _, p := range [][2]int{{12, 14}, {8, 12}, {10, 13}, {11, 11}} {
		check("set_resolution", chip.SetResolution(p[0], p[1]) == nil)
		rb, tb, err := chip.GetResolution()
		check("get_resolution", err == nil && rb == p[0] && tb == p[1])
		_, _, err = chip.Read()
		check("read at resolution", err == nil)
	}
	check("set_resolution default", chip.SetResolution(12, 14) == nil)
	ct, err := chip.ConversionTime()
	check("conversion time", err == nil && ct > 0.005 && ct < 0.03)

	check("heater on", chip.SetHeater(true) == nil)
	on, err := chip.IsHeaterOn()
	check("heater on readback", err == nil && on)
	check("heater off", chip.SetHeater(false) == nil)
	on, err = chip.IsHeaterOn()
	check("heater off readback", err == nil && !on)
	low, err := chip.VDDLow()
	check("vdd ok", err == nil && !low)
	_, err = chip.FirmwareRevision()
	check("firmware readable", err == nil)

	dp, err := chip.DewPoint()
	check("dew point plausible", err == nil && dp > -60 && dp < 60)
	check("software_reset", chip.SoftReset() == nil)
	_, _, err = chip.Read()
	check("read after software reset", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

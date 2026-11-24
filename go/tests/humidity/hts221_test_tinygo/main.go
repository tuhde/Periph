//go:build tinygo

// HTS221 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an HTS221 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

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
	conn := connection.NewI2CConnection(i2c, 0x5F, nil, nil)
	chip := humidity.NewHTS221Full(conn)

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
	id, err := chip.WhoAmI()
	check("who_am_i", err == nil && id == 0xBC)
	cal := chip.ReadCalibration()
	check("calibration_plausible", cal.H1rH > cal.H0rH && cal.T1degC > cal.T0degC)
	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("read_temperature_range", t > -40 && t < 85)
	check("read_humidity_range", rh >= 0 && rh <= 100)
	h, err := chip.ReadHumidity()
	check("read_humidity_single", err == nil && h >= 0 && h <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature_single", err == nil && tc > -40 && tc < 85)
	dew, err := chip.DewPoint()
	check("dew_point_below_temperature", err == nil && dew <= tc+1)
	check("set_averaging", chip.SetAveraging(32, 64) == nil)
	ta, ha, err := chip.GetAveraging()
	check("averaging_roundtrip", err == nil && ta == 32 && ha == 64)
	check("set_averaging default", chip.SetAveraging(16, 32) == nil)
	check("set_output_data_rate", chip.SetOutputDataRate(1) == nil)
	hz, err := chip.GetOutputDataRate()
	check("odr_roundtrip", err == nil && hz == 1)
	time.Sleep(1500 * time.Millisecond)
	_, crh, err := chip.ReadContinuous()
	check("continuous_read", err == nil && crh >= 0 && crh <= 100)
	check("set_output_data_rate one-shot", chip.SetOutputDataRate(0) == nil)
	check("heater on", chip.SetHeater(true) == nil)
	on, err := chip.IsHeaterOn()
	check("heater_on_readback", err == nil && on)
	check("heater off", chip.SetHeater(false) == nil)
	on, err = chip.IsHeaterOn()
	check("heater_off_readback", err == nil && !on)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

//go:build tinygo

// BH1750 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an BH1750 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
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
	conn := connection.NewI2CConnection(i2c, 0x23, nil, nil)
	chip := light.NewBH1750Full(conn)

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
	inRange := func(v float32) bool { return v >= 0 && v <= 120000 }
	lux, err := chip.Read()
	check("read_range", err == nil && inRange(lux))
	raw, err := chip.ReadRaw()
	check("read_raw_range", err == nil && raw <= 65535)
	for _, m := range []struct {
		name string
		mode light.BH1750Mode
	}{{"h", light.BH1750ModeH}, {"h2", light.BH1750ModeH2}, {"l", light.BH1750ModeL}} {
		v, err := chip.MeasureOnce(m.mode)
		check("measure_once_"+m.name, err == nil && inRange(v))
	}
	check("lux_per_count_default", math.Abs(float64(chip.LuxPerCount(light.BH1750ModeH))-1/1.2) < 1e-5)
	check("measurement_time_default", math.Abs(float64(chip.MeasurementTime(light.BH1750ModeH))-0.180) < 1e-5)
	check("start_continuous", chip.StartContinuous(light.BH1750ModeH) == nil)
	time.Sleep(200 * time.Millisecond)
	c, err := chip.ReadContinuous()
	check("read_continuous_range", err == nil && inRange(c))
	check("set_measurement_time", chip.SetMeasurementTime(138) == nil)
	check("mtreg_roundtrip", chip.GetMeasurementTimeRegister() == 138)
	check("measurement_time_scaled", math.Abs(float64(chip.MeasurementTime(light.BH1750ModeH))-0.360) < 1e-5)
	v, err := chip.MeasureOnce(light.BH1750ModeH)
	check("read_with_mtreg", err == nil && inRange(v))
	check("set_sensitivity", chip.SetSensitivity(1.0) == nil)
	check("sensitivity_default", chip.GetMeasurementTimeRegister() == 69)
	chip.PowerDown()
	chip.PowerOn()
	check("reset", chip.Reset() == nil)
	v, err = chip.MeasureOnce(light.BH1750ModeH)
	check("read_after_reset", err == nil && inRange(v))
	chip.PowerDown()

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

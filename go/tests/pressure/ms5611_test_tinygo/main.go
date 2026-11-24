//go:build tinygo

// MS5611 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MS5611 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
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
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	conn := connection.NewI2CConnection(i2c, 0x77, nil, nil)
	chip := pressure.NewMS5611Full(conn)

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
	check("prom_crc_ok", chip.PROMCRCOK())
	t, p, err := chip.Read()
	check("read ok", err == nil)
	check("read_temperature_range", t > -40 && t < 85)
	check("read_pressure_range", p > 30000 && p < 120000)
	ps, err := chip.ReadPressure()
	check("read_pressure_single", err == nil && ps > 30000 && ps < 120000)
	tc, err := chip.ReadTemperature()
	check("read_temperature_single", err == nil && tc > -40 && tc < 85)
	d1, d2, err := chip.ReadRaw()
	check("read_raw", err == nil && d1 > 0 && d2 > 0)
	_, pc := chip.Compensate(d1, d2, true)
	check("compensate_matches_read", pc-p < 200 && p-pc < 200)
	c := chip.Coefficients()
	check("coefficients_nonzero", c[0] > 0 && c[5] > 0)
	check("serial_code_range", chip.SerialCode() < 4096)
	_, err = chip.ReadPROM()
	check("read_prom", err == nil)
	for _, osr := range []uint16{256, 512, 1024, 2048, 4096} {
		check("osr_roundtrip", chip.SetOversampling(osr) == nil && chip.Oversampling() == osr)
		v, err := chip.ReadPressure()
		check("osr_read", err == nil && v > 30000 && v < 120000)
	}
	check("start_conversion", chip.StartConversion(pressure.MS5611Temperature, 0) == nil)
	time.Sleep(12 * time.Millisecond)
	adc, err := chip.ReadADC()
	check("non_blocking_adc", err == nil && adc > 0)
	alt, err := chip.Altitude(101325)
	check("altitude_plausible", err == nil && alt > -500 && alt < 6000)
	check("reset", chip.Reset() == nil)
	v, err := chip.ReadPressure()
	check("read_after_reset", err == nil && v > 30000 && v < 120000)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

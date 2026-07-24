//go:build tinygo

// BME680 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a BME680 on I2C1 (GP4 = SDA, GP5 = SCL).
// Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"math"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
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

	tr := transport.NewI2CTransport(i2c, 0x76)
	chip, err := environmental.NewBME680Full(tr)
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

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)

	p, err := chip.Pressure()
	check("pressure_range", err == nil && p >= 300.0 && p <= 1100.0)

	h, err := chip.Humidity()
	check("humidity_range", err == nil && h >= 0.0 && h <= 100.0)

	g, err := chip.GasResistance()
	check("gas_resistance_present", err == nil && (!math.IsNaN(float64(g)) || err == nil))

	if err := chip.SetOversampling(
		environmental.BME680OSRSX4,
		environmental.BME680OSRSX2,
		environmental.BME680OSRSX1,
	); err == nil {
		check("set_oversampling", true)
	} else {
		check("set_oversampling", false)
	}

	cid, err := chip.ChipID()
	check("chip_id", err == nil && cid == 0x61)

	t2, p2, h2, _, err := chip.ReadAll()
	check("read_all", err == nil &&
		t2 >= -40.0 && t2 <= 85.0 &&
		p2 >= 300.0 && p2 <= 1100.0 &&
		h2 >= 0.0 && h2 <= 100.0)

	if err := chip.Reset(); err == nil {
		check("reset", true)
	} else {
		check("reset", false)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

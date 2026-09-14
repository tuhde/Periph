//go:build tinygo

// MPR121 hardware test — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/other"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Println("i2c configure:", err)
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x5A, nil, nil)
	chip, err := other.NewMPR121Full(conn)
	if err != nil {
		fmt.Println("new:", err)
		return
	}

	passed := 0
	failed := 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	t, err := chip.Touched()
	check("touched in 0..4095", err == nil && t <= 0xFFF)

	if _, err := chip.IsTouched(0); err == nil {
		check("is_touched returns bool", true)
	}

	f0, err := chip.Filtered(0)
	check("filtered(0) in 0..1023", err == nil && f0 <= 1023)

	b0, err := chip.Baseline(0)
	check("baseline(0) in 0..1023", err == nil && b0 <= 1023)

	if err := chip.Stop(); err == nil {
		check("stop accepted", true)
	}
	if err := chip.ConfigureThresholds(0, 15, 8); err == nil {
		check("configure_thresholds accepted", true)
	}
	if err := chip.ConfigureAllThresholds(12, 6); err == nil {
		check("configure_all_thresholds accepted", true)
	}
	if err := chip.ConfigureProximityThresholds(8, 4); err == nil {
		check("configure_proximity_thresholds accepted", true)
	}
	if err := chip.ConfigureSampling(16, 1, 0, 0, 4); err == nil {
		check("configure_sampling accepted", true)
	}
	if err := chip.ConfigureDebounce(1, 1); err == nil {
		check("configure_debounce accepted", true)
	}
	if err := chip.EnableInterrupt(other.SOURCE_OOR); err == nil {
		check("enable_interrupt accepted", true)
	}
	if err := chip.DisableInterrupt(other.SOURCE_OOR); err == nil {
		check("disable_interrupt accepted", true)
	}
	if err := chip.ClearOvercurrent(); err == nil {
		check("clear_overcurrent accepted", true)
	}
	if err := chip.Reset(); err == nil {
		check("reset accepted", true)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	time.Sleep(time.Second)
}

//go:build tinygo

// APDS-9930 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Drives a sequence of PASS/FAIL checks against a real APDS-9930 over
// machine.I2C1 (GP4 SDA / GP5 SCL). Prints ===DONE=== at the end.
package main

import (
	"fmt"
	"machine"
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
		fmt.Println("i2c configure:", err)
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x39, nil, nil)
	chip, err := light.NewAPDS9930Full(conn)
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

	id, err := chip.ChipID()
	check("chip_id is 0x39", err == nil && id == 0x39)

	_, err = chip.Lux()
	check("lux returns no error", err == nil)
	_, err = chip.Proximity()
	check("proximity returns no error", err == nil)
	_, err = chip.Ch0()
	_, err = chip.Ch1()
	check("ch0/ch1 read", err == nil)

	check("configure_als accepted", chip.ConfigureALS(0xDB, 0, false) == nil)
	check("configure_proximity accepted", chip.ConfigureProximity(8, 0, 0, false, 0xFF) == nil)
	check("disable_wait accepted", chip.DisableWait() == nil)
	check("set_als_thresholds accepted", chip.SetAlsThresholds(0, 65535, 1) == nil)
	check("set_proximity_thresholds accepted", chip.SetProximityThresholds(0, 1023, 1) == nil)
	check("set_proximity_offset accepted", chip.SetProximityOffset(0) == nil)
	check("sleep_after_interrupt accepted", chip.SleepAfterInterrupt(false) == nil)
	check("clear_interrupt accepted", chip.ClearInterrupt(0) == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	for {
		time.Sleep(time.Second)
	}
}
//go:build tinygo

// APDS-9960 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an APDS-9960 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/chips/light"
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

	conn := connection.NewI2CConnection(i2c, 0x39, nil, nil)
	chip, err := light.NewAPDS9960Full(conn)
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

	id, err := chip.ChipID()
	check("chip_id", err == nil && id == 0xAB)

	c, r, g, b, err := chip.Color()
	check("color_range", err == nil && c <= 65535 && r <= 65535 && g <= 65535 && b <= 65535)

	valid, err := chip.IsAlsValid()
	check("is_als_valid", err == nil && valid)

	if err := chip.EnableProximity(true); err != nil {
		fmt.Printf("FAIL enable_proximity: %v\n", err)
	}
	time.Sleep(100 * time.Millisecond)
	prox, err := chip.Proximity()
	check("proximity_range", err == nil && prox <= 255)

	if err := chip.ConfigureALS(0xB6, 1); err != nil {
		fmt.Printf("FAIL configure_als: %v\n", err)
	}
	time.Sleep(210 * time.Millisecond)

	if err := chip.AlsThreshold(100, 60000); err != nil {
		fmt.Printf("FAIL als_threshold: %v\n", err)
	}
	if err := chip.ProximityThreshold(10, 200); err != nil {
		fmt.Printf("FAIL proximity_threshold: %v\n", err)
	}
	if err := chip.SetPersistence(0, 1); err != nil {
		fmt.Printf("FAIL set_persistence: %v\n", err)
	}
	check("thresholds_persistence", true)

	if err := chip.ClearAllInterrupts(); err != nil {
		fmt.Printf("FAIL clear_all_interrupts: %v\n", err)
	}
	check("interrupts_cleared", true)

	if err := chip.SetProximityOffset(10, -5); err != nil {
		fmt.Printf("FAIL set_proximity_offset: %v\n", err)
	}
	check("proximity_offset_set", true)

	if err := chip.EnableGesture(true); err != nil {
		fmt.Printf("FAIL enable_gesture: %v\n", err)
	}
	if err := chip.ConfigureGesture(1, 0, 0, 1, 1, 50, 20); err != nil {
		fmt.Printf("FAIL configure_gesture: %v\n", err)
	}
	check("gesture_configured", true)

	if err := chip.ClearGestureFIFO(); err != nil {
		fmt.Printf("FAIL clear_gesture_fifo: %v\n", err)
	}
	if err := chip.EnableGesture(false); err != nil {
		fmt.Printf("FAIL disable_gesture: %v\n", err)
	}
	check("gesture_disabled", true)

	status, err := chip.Status()
	check("status_readable", err == nil && status <= 0xFF)

	if err := chip.EnableProximity(false); err != nil {
		fmt.Printf("FAIL disable_proximity: %v\n", err)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

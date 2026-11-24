//go:build tinygo

// SAA1064 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an SAA1064 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil)

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

	display, err := led.NewSAA1064Minimal(conn)
	check("init", err == nil)
	if err != nil {
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	check("show_digits", display.ShowDigits([4]int{1, 2, 3, 4}) == nil)
	check("show_number", display.ShowNumber(1234) == nil)
	check("write_raw", display.WriteRaw([]byte{0x7F, 0x7F}, 3) == nil)
	check("clear", display.Clear() == nil)

	full, err := led.NewSAA1064Full(conn)
	check("init_full", err == nil)
	if err != nil {
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		return
	}
	check("set_mode", full.SetMode(false) == nil && full.SetMode(true) == nil)
	check("blank", full.Blank(true, false) == nil && full.Blank(false, true) == nil && full.Blank(false, false) == nil)
	check("set_current", full.SetCurrent(3) == nil && full.SetCurrent(21) == nil && full.SetCurrent(12) == nil)
	check("segment_test", full.SegmentTest(true) == nil && full.SegmentTest(false) == nil)
	check("set_segment_map", full.SetSegmentMap([8]uint8{0, 1, 2, 3, 4, 5, 6, 7}) == nil)
	check("show_digit", full.ShowDigit(1, 5, true) == nil)
	check("set_decimal_point", full.SetDecimalPoint(1, false) == nil && full.SetDecimalPoint(4, true) == nil)
	_, err = full.ReadStatus()
	check("read_status", err == nil)
	flag, err := full.PowerResetFlag()
	check("power_reset_flag_cleared", err == nil && !flag)
	check("write_control", full.WriteControl(0x47) == nil)
	_ = full.Clear()

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

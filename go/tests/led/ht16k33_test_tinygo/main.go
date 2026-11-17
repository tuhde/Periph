//go:build tinygo

// HT16K33 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a HT16K33 on I2C1 (GP4 = SDA, GP5 = SCL).
// Prints PASS/FAIL per check and ends with the standard ===DONE: ... === line.
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
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x70, nil, nil)

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

	matrix, err := led.NewHT16K33Minimal(conn)
	if err != nil {
		fmt.Println("FAIL init:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	check("constants", led.HT16K33Commons == 8 && led.HT16K33Rows == 16)
	check("set_pixel", matrix.SetPixel(0, 0, true) == nil && matrix.SetPixel(7, 15, true) == nil)
	check("set_pixel_range", matrix.SetPixel(8, 0, true) != nil && matrix.SetPixel(0, 16, true) != nil)
	check("show", matrix.Show() == nil)
	check("write_raw", matrix.WriteRaw([]byte{0xFF, 0x00}, 0) == nil)
	check("write_raw_overflow", matrix.WriteRaw([]byte{0, 0}, 15) != nil)
	check("set_brightness", matrix.SetBrightness(0) == nil && matrix.SetBrightness(15) == nil)
	check("clear", matrix.Clear() == nil)

	full, err := led.NewHT16K33Full(conn)
	if err != nil {
		fmt.Println("FAIL init full:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	_ = full.SetPixel(3, 4, true)
	_ = full.Show()
	on, _ := full.GetPixel(3, 4)
	off, _ := full.GetPixel(3, 5)
	check("get_pixel", on && !off)
	check("set_blink", full.SetBlink(2) == nil && full.SetBlink(1) == nil && full.SetBlink(0.5) == nil && full.SetBlink(0) == nil)
	check("display_off_on", full.DisplayOff() == nil && full.DisplayOn() == nil)
	check("standby_wake", full.Standby() == nil && full.Wake() == nil)
	check("set_int_output", full.SetIntOutput(true, false) == nil && full.SetIntOutput(true, true) == nil && full.SetIntOutput(false, false) == nil)
	_, err = full.ReadKeys()
	check("read_keys", err == nil)
	_, err = full.KeyPressed()
	check("key_pressed", err == nil)
	_ = full.Clear()

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

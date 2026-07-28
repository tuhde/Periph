//go:build tinygo

// PCF8576 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a PCF8576 on I2C1 (GP4 = SDA, GP5 = SCL).
// Prints PASS/FAIL per check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/display"
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

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil)
	chip, err := display.NewPCF8576Minimal(conn)
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

	if err := chip.Clear(); err != nil {
		fmt.Printf("FAIL clear: %v\n", err)
	}
	check("clear", err == nil)

	bytes := [4]byte{0xED, 0x60, 0xA7, 0xE3}
	if err := chip.WriteRaw(0, bytes[:]); err != nil {
		fmt.Printf("FAIL write_raw: %v\n", err)
	}
	check("write_raw", err == nil)

	if err := chip.SetDigit7seg(0, 0xED); err != nil {
		fmt.Printf("FAIL set_digit_7seg: %v\n", err)
	}
	check("set_digit_7seg", err == nil)

	check("seven_seg_0", display.PCF8576SevenSeg[0] == 0xED)
	check("seven_seg_9", display.PCF8576SevenSeg[9] == 0xEB)

	conn2 := connection.NewI2CConnection(i2c, 0x38, nil, nil)
	full, err := display.NewPCF8576Full(conn2)
	if err != nil {
		fmt.Printf("FAIL new full: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	if err := full.Enable(); err != nil {
		fmt.Printf("FAIL enable: %v\n", err)
	}
	if err := full.Disable(); err != nil {
		fmt.Printf("FAIL disable: %v\n", err)
	}
	check("enable_disable", err == nil)

	if err := full.SetMode(display.PCF8576Backplanes4, display.PCF8576Bias1_3); err != nil {
		fmt.Printf("FAIL set_mode: %v\n", err)
	}
	if err := full.SetBlink(display.PCF8576Blink2Hz, false); err != nil {
		fmt.Printf("FAIL set_blink: %v\n", err)
	}
	if err := full.SetBank(display.PCF8576Bank0, display.PCF8576Bank0); err != nil {
		fmt.Printf("FAIL set_bank: %v\n", err)
	}
	if err := full.DeviceSelect(0); err != nil {
		fmt.Printf("FAIL device_select: %v\n", err)
	}
	check("full_configure", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

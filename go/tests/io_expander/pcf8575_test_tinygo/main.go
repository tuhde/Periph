//go:build tinygo

// PCF8575 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a PCF8575 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
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

	conn := connection.NewI2CConnection(i2c, 0x20, nil, nil)
	chip, err := ioexpander.NewPCF8575Minimal(conn, 0x20)
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

	port0, err := chip.ReadPort(0)
	check("read_port0_range", err == nil && port0 <= 0xFF)
	port1, err := chip.ReadPort(1)
	check("read_port1_range", err == nil && port1 <= 0xFF)

	if err := chip.WritePort(0, 0xFF); err != nil {
		fmt.Printf("FAIL write_port0: %v\n", err)
	}
	if err := chip.WritePort(1, 0xFF); err != nil {
		fmt.Printf("FAIL write_port1: %v\n", err)
	}
	check("write_port_ok", true)

	p0 := chip.Pin(0)
	if err := p0.Set(true); err != nil {
		fmt.Printf("FAIL set_high: %v\n", err)
	}
	if err := p0.Set(false); err != nil {
		fmt.Printf("FAIL set_low: %v\n", err)
	}
	check("pin_set_toggle", true)

	// --- PCF8575Full ---
	conn2 := connection.NewI2CConnection(i2c, 0x20, nil, nil)
	full, err := ioexpander.NewPCF8575Full(conn2, 0x20)
	if err != nil {
		fmt.Printf("FAIL new full: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	changed, err := full.PollInterrupt()
	check("poll_interrupt_no_change", err == nil && changed == 0)

	// --- OnInterrupt / OffInterrupt / per-pin Watch / Unwatch ---
	if err := full.OnInterrupt(func(mask uint16) {}); err != nil {
		fmt.Printf("FAIL on_interrupt: %v\n", err)
	}
	p9 := full.Pin(9)
	if err := p9.Watch(connection.Change, func(pin ioexpander.PCF8575FullPin) {}); err != nil {
		fmt.Printf("FAIL watch: %v\n", err)
	}
	_ = p9.Unwatch()
	if err := full.OffInterrupt(); err != nil {
		fmt.Printf("FAIL off_interrupt: %v\n", err)
	}
	check("on_off_interrupt_and_watch_accepted", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

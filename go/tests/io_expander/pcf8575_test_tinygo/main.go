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

	tr := transport.NewI2CTransport(i2c, 0x20)
	chip, err := ioexpander.NewPCF8575Minimal(tr, 0x20)
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
	tr2 := transport.NewI2CTransport(i2c, 0x20)
	full, err := ioexpander.NewPCF8575Full(tr2, 0x20)
	if err != nil {
		fmt.Printf("FAIL new full: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	changed, err := full.ClearInterrupt()
	check("clear_interrupt_no_change", err == nil && changed == 0)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

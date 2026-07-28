//go:build tinygo

// PCF8574 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a PCF8574 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line. The test runner (go/test_tinygo.sh) reads
// the serial output and reports exit code 0/1/2 based on the
// ===DONE=== line.
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
		Frequency: 100_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x20, nil, nil)
	chip, err := ioexpander.NewPCF8574Minimal(conn, 0x20)
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

	port, err := chip.ReadPort0()
	check("read_port_range", err == nil && port <= 0xFF)

	if err := chip.WritePort0(0b00001111); err != nil {
		fmt.Printf("FAIL write_port: %v\n", err)
	}
	if err := chip.WritePort0(0xFF); err != nil {
		fmt.Printf("FAIL write_port_restore: %v\n", err)
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

	// --- PCF8574Full ---
	conn2 := connection.NewI2CConnection(i2c, 0x20, nil, nil)
	full, err := ioexpander.NewPCF8574Full(conn2, 0x20)
	if err != nil {
		fmt.Printf("FAIL new full: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	changed, err := full.PollInterrupt()
	check("poll_interrupt_no_change", err == nil && changed == 0)

	if err := full.WritePort0(0xAA); err != nil {
		fmt.Printf("FAIL full_write: %v\n", err)
	}
	if err := full.WritePort0(0xFF); err != nil {
		fmt.Printf("FAIL full_write_restore: %v\n", err)
	}
	check("full_write_port_ok", true)

	// --- OnInterrupt / OffInterrupt / per-pin Watch / Unwatch ---
	if err := full.OnInterrupt(func(mask uint8) {}); err != nil {
		fmt.Printf("FAIL on_interrupt: %v\n", err)
	}
	p5 := full.Pin(5)
	if err := p5.Watch(connection.Change, func(pin ioexpander.PCF8574FullPin) {}); err != nil {
		fmt.Printf("FAIL watch: %v\n", err)
	}
	_ = p5.Unwatch()
	if err := full.OffInterrupt(); err != nil {
		fmt.Printf("FAIL off_interrupt: %v\n", err)
	}
	check("on_off_interrupt_and_watch_accepted", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

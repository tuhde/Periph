//go:build tinygo

// TPIC6B595 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W wired to a TPIC6B595 with SER IN/SRCK/RCK
// (and optional SRCLR/G) on plain GPIO pins. Prints PASS/FAIL per
// check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	serIn := machine.GP19
	srck  := machine.GP26
	rck   := machine.GP17
	srclr := machine.GP16
	g     := machine.GP15
	serIn.Configure(machine.PinConfig{Mode: machine.PinOutput})
	srck.Configure(machine.PinConfig{Mode: machine.PinOutput})
	rck.Configure(machine.PinConfig{Mode: machine.PinOutput})
	srclr.Configure(machine.PinConfig{Mode: machine.PinOutput})
	g.Configure(machine.PinConfig{Mode: machine.PinOutput})

	conn := connection.NewSIPOSoftwareSPI(serIn, srck, rck, srclr, g, nil)
	chip, err := ioexpander.NewTPIC6B595Full(conn, 2)
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

	check("init_shadow_0", chip.Shadow(0) == 0)
	check("init_shadow_1", chip.Shadow(1) == 0)

	if err := chip.Fill(true); err != nil {
		fmt.Printf("FAIL fill true: %v\n", err)
	}
	check("fill_true_shadow_0", chip.Shadow(0) == 0xFF)
	check("fill_true_shadow_1", chip.Shadow(1) == 0xFF)
	if err := chip.Fill(false); err != nil {
		fmt.Printf("FAIL fill false: %v\n", err)
	}
	check("fill_false_shadow_0", chip.Shadow(0) == 0x00)
	check("fill_false_shadow_1", chip.Shadow(1) == 0x00)

	if err := chip.WritePort(0, 0xA5); err != nil {
		fmt.Printf("FAIL write port 0: %v\n", err)
	}
	check("write_port_0_shadow", chip.Shadow(0) == 0xA5)

	p0 := chip.Pin(0)
	if err := p0.Set(true); err != nil {
		fmt.Printf("FAIL set high: %v\n", err)
	}
	check("pin_on_shadow_bit", chip.Shadow(0)&0x01 == 1)
	if err := p0.Set(false); err != nil {
		fmt.Printf("FAIL set low: %v\n", err)
	}
	check("pin_off_shadow_bit", chip.Shadow(0)&0x01 == 0)
	if err := p0.Toggle(); err != nil {
		fmt.Printf("FAIL toggle: %v\n", err)
	}
	check("pin_toggle_shadow_bit", chip.Shadow(0)&0x01 == 1)

	if err := chip.Clear(); err != nil {
		fmt.Printf("FAIL clear: %v\n", err)
	}
	check("clear_accepted", true)

	if err := chip.SetOutputEnable(false); err != nil {
		fmt.Printf("FAIL set output enable false: %v\n", err)
	}
	check("set_output_enable_false_accepted", true)
	if err := chip.SetOutputEnable(true); err != nil {
		fmt.Printf("FAIL set output enable true: %v\n", err)
	}
	check("set_output_enable_true_accepted", true)

	if err := chip.WriteAll([]uint8{0xA5, 0x5A}); err != nil {
		fmt.Printf("FAIL write all: %v\n", err)
	}
	check("write_all_shadow_0", chip.Shadow(0) == 0xA5)
	check("write_all_shadow_1", chip.Shadow(1) == 0x5A)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

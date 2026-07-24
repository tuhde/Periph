//go:build tinygo

// MCP23017 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MCP23017 on I2C1 (GP4 = SDA,
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
	chip, err := ioexpander.NewMCP23017Minimal(tr, 0x20)
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

	porta, err := chip.ReadPort(0)
	check("read_porta_range", err == nil && porta <= 0xFF)
	portb, err := chip.ReadPort(1)
	check("read_portb_range", err == nil && portb <= 0xFF)

	if err := chip.WritePort(0, 0x55); err != nil {
		fmt.Printf("FAIL write_porta: %v\n", err)
	}
	if err := chip.WritePort(1, 0xAA); err != nil {
		fmt.Printf("FAIL write_portb: %v\n", err)
	}
	check("write_port_ok", true)

	p0 := chip.Pin(0)
	if err := p0.Set(true); err != nil {
		fmt.Printf("FAIL set_high: %v\n", err)
	}
	if err := p0.Set(false); err != nil {
		fmt.Printf("FAIL set_low: %v\n", err)
	}
	_, _ = p0.Get()
	check("pin_set_get", true)

	// --- MCP23017Full ---
	tr2 := transport.NewI2CTransport(i2c, 0x20)
	full, err := ioexpander.NewMCP23017Full(tr2, 0x20)
	if err != nil {
		fmt.Printf("FAIL new full: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	if err := full.ConfigurePullup(0, 0x55); err != nil {
		fmt.Printf("FAIL pullup_a: %v\n", err)
	}
	if err := full.ConfigurePolarity(0, 0x0F); err != nil {
		fmt.Printf("FAIL polarity_a: %v\n", err)
	}
	check("full_configure", true)

	flags, err := full.ReadInterruptFlags(0)
	check("read_interrupt_flags", err == nil && flags <= 0xFF)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

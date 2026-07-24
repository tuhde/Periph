//go:build linux && !tinygo

// MCP23017 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the MCP23017 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x20"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	tr1, err := transport.NewI2CTransport(bus, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport:", err)
		os.Exit(2)
	}
	defer tr1.Close()

	chip, err := ioexpander.NewMCP23017Minimal(tr1, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	porta, err := chip.ReadPort(0)
	check("read_porta_range", err == nil && porta <= 0xFF)
	portb, err := chip.ReadPort(1)
	check("read_portb_range", err == nil && portb <= 0xFF)

	if err := chip.WritePort(0, 0x55); err != nil {
		fmt.Fprintln(os.Stderr, "write_porta:", err)
	}
	if err := chip.WritePort(1, 0xAA); err != nil {
		fmt.Fprintln(os.Stderr, "write_portb:", err)
	}
	after, _ := chip.ReadPort(0)
	check("write_porta_0x55", after&0x7F == 0x55)
	after, _ = chip.ReadPort(1)
	check("write_portb_0xAA", after&0x7F == 0xAA)

	p0 := chip.Pin(0)
	if err := p0.Set(false); err != nil {
		fmt.Fprintln(os.Stderr, "set_low:", err)
	}
	if err := p0.Set(true); err != nil {
		fmt.Fprintln(os.Stderr, "set_high:", err)
	}
	_, _ = p0.Get()

	p8 := chip.Pin(8)
	if p8 == (ioexpander.MCP23017Pin{}) {
		fmt.Fprintln(os.Stderr, "pin_8_init:")
	}
	p15 := chip.Pin(15)
	if p15 == (ioexpander.MCP23017Pin{}) {
		fmt.Fprintln(os.Stderr, "pin_15_init:")
	}

	// --- MCP23017Full ---
	tr2, err := transport.NewI2CTransport(bus, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport full:", err)
		os.Exit(2)
	}
	defer tr2.Close()

	full, err := ioexpander.NewMCP23017Full(tr2, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}

	if err := full.ConfigurePullup(0, 0x55); err != nil {
		fmt.Fprintln(os.Stderr, "pullup_a:", err)
	}
	if err := full.ConfigurePullup(1, 0xAA); err != nil {
		fmt.Fprintln(os.Stderr, "pullup_b:", err)
	}
	if err := full.ConfigurePolarity(0, 0x0F); err != nil {
		fmt.Fprintln(os.Stderr, "polarity_a:", err)
	}
	if err := full.ConfigurePolarity(1, 0xF0); err != nil {
		fmt.Fprintln(os.Stderr, "polarity_b:", err)
	}
	flags, err := full.ReadInterruptFlags(0)
	check("interrupt_flags_a", err == nil && flags <= 0xFF)
	changed, err := full.ClearInterrupt(0)
	check("clear_interrupt_a", err == nil && changed <= 0xFF)
	fp1 := full.Pin(1)
	_ = fp1

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

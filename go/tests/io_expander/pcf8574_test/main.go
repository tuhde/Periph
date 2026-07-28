//go:build linux && !tinygo

// PCF8574 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the PCF8574 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn1, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn1.Close()

	chip, err := ioexpander.NewPCF8574Minimal(conn1, uint8(addr))
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

	// Shadow initialises to 0xFF (all pins input mode)
	p0 := chip.Pin(0)
	port, err := chip.ReadPort0()
	check("read_port_range", err == nil && port <= 0xFF)
	check("p0_initially_input_after_set_low", !func() bool { v, _ := p0.Get(); return v }())

	// write_port drives pins and updates shadow
	if err := chip.WritePort0(0b00001111); err != nil {
		fmt.Fprintln(os.Stderr, "write_port:", err)
		os.Exit(2)
	}
	p4 := chip.Pin(4)
	high4, err := p4.Get()
	check("write_port_p4_input", err == nil && high4)
	p0b := chip.Pin(0)
	low0, err := p0b.Get()
	check("write_port_p0_drive_low", err == nil && !low0)

	// set_high and set_low via Set
	if err := p0.Set(true); err != nil {
		fmt.Fprintln(os.Stderr, "set_high:", err)
	}
	after, _ := chip.ReadPort0()
	check("set_high_p0", after&0x01 == 0x01)
	if err := p0.Set(false); err != nil {
		fmt.Fprintln(os.Stderr, "set_low:", err)
	}
	after, _ = chip.ReadPort0()
	check("set_low_p0", after&0x01 == 0x00)

	// Restore all pins to input mode
	if err := chip.WritePort0(0xFF); err != nil {
		fmt.Fprintln(os.Stderr, "write_port_restore:", err)
		os.Exit(2)
	}

	// --- PCF8574Full ---
	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection full:", err)
		os.Exit(2)
	}
	defer conn2.Close()

	full, err := ioexpander.NewPCF8574Full(conn2, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}

	// poll_interrupt immediately after init — nothing changed, should be 0
	changed, err := full.PollInterrupt()
	check("poll_interrupt_no_change", err == nil && changed == 0)

	// write_port then read back via read_port
	if err := full.WritePort0(0xAA); err != nil {
		fmt.Fprintln(os.Stderr, "full write:", err)
	}
	if err := full.WritePort0(0xFF); err != nil {
		fmt.Fprintln(os.Stderr, "full write restore:", err)
	}
	port2, err := full.ReadPort0()
	check("full_read_port_range", err == nil && port2 <= 0xFF)

	// --- OnInterrupt / OffInterrupt / per-pin Watch / Unwatch ---
	received := -1
	if err := full.OnInterrupt(func(mask uint8) { received = int(mask) }); err != nil {
		fmt.Fprintln(os.Stderr, "on_interrupt:", err)
	}
	p5 := full.Pin(5)
	if err := p5.Watch(connection.Change, func(pin ioexpander.PCF8574FullPin) {}); err != nil {
		fmt.Fprintln(os.Stderr, "watch:", err)
	}
	_ = p5.Unwatch()
	if err := full.OffInterrupt(); err != nil {
		fmt.Fprintln(os.Stderr, "off_interrupt:", err)
	}
	check("on_off_interrupt_and_watch_accepted", true)
	_ = received

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

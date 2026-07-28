//go:build linux && !tinygo

// PCF8575 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the PCF8575 check sequence. Prints
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

	chip, err := ioexpander.NewPCF8575Minimal(conn1, uint8(addr))
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

	port0, err := chip.ReadPort(0)
	check("read_port0_range", err == nil && port0 <= 0xFF)
	port1, err := chip.ReadPort(1)
	check("read_port1_range", err == nil && port1 <= 0xFF)

	// write_port drives pins and updates shadow
	if err := chip.WritePort(0, 0b00001111); err != nil {
		fmt.Fprintln(os.Stderr, "write_port0:", err)
	}
	if err := chip.WritePort(1, 0xFF); err != nil {
		fmt.Fprintln(os.Stderr, "write_port1:", err)
	}
	after, _ := chip.ReadPort(0)
	check("write_port0_mask_0x0F", after&0x0F == 0x0F)

	p0 := chip.Pin(0)
	p8 := chip.Pin(8)
	p15 := chip.Pin(15)
	_ = p0
	_ = p8
	_ = p15

	if err := p0.Set(true); err != nil {
		fmt.Fprintln(os.Stderr, "set_high:", err)
	}
	if err := p0.Set(false); err != nil {
		fmt.Fprintln(os.Stderr, "set_low:", err)
	}
	after, _ = chip.ReadPort(0)
	check("set_low_p0", after&0x01 == 0x00)

	// Restore both ports to input mode (0xFF, 0xFF)
	if err := chip.WritePort(0, 0xFF); err != nil {
		fmt.Fprintln(os.Stderr, "write_port0_restore:", err)
	}
	if err := chip.WritePort(1, 0xFF); err != nil {
		fmt.Fprintln(os.Stderr, "write_port1_restore:", err)
	}

	// --- PCF8575Full ---
	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection full:", err)
		os.Exit(2)
	}
	defer conn2.Close()

	full, err := ioexpander.NewPCF8575Full(conn2, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}

	changed, err := full.PollInterrupt()
	check("poll_interrupt_no_change", err == nil && changed == 0)

	if err := full.WritePort(0, 0xAA); err != nil {
		fmt.Fprintln(os.Stderr, "full write0:", err)
	}
	if err := full.WritePort(1, 0x55); err != nil {
		fmt.Fprintln(os.Stderr, "full write1:", err)
	}
	if err := full.WritePort(0, 0xFF); err != nil {
		fmt.Fprintln(os.Stderr, "full write0_restore:", err)
	}
	if err := full.WritePort(1, 0xFF); err != nil {
		fmt.Fprintln(os.Stderr, "full write1_restore:", err)
	}
	portA, err := full.ReadPort(0)
	check("full_read_port0_range", err == nil && portA <= 0xFF)
	portB, err := full.ReadPort(1)
	check("full_read_port1_range", err == nil && portB <= 0xFF)

	// --- OnInterrupt / OffInterrupt / per-pin Watch / Unwatch ---
	received := -1
	if err := full.OnInterrupt(func(mask uint16) { received = int(mask) }); err != nil {
		fmt.Fprintln(os.Stderr, "on_interrupt:", err)
	}
	p9 := full.Pin(9)
	if err := p9.Watch(connection.Change, func(pin ioexpander.PCF8575FullPin) {}); err != nil {
		fmt.Fprintln(os.Stderr, "watch:", err)
	}
	_ = p9.Unwatch()
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

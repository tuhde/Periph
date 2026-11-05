//go:build linux && !tinygo

// MCP23008 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the MCP23008 check sequence. Prints
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
	addrArg, err := strconv.ParseUint(envOr("I2C_ADDR", "0x20"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}
	ADDR := uint8(addrArg)

	conn, err := connection.NewI2CConnection(bus, ADDR, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := ioexpander.NewMCP23008Minimal(conn, ADDR)
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

	// Wiring for the loopback checks: jumper GP0–GP3 to GP4–GP7 (GPn <-> GPn+4).
	port, err := chip.ReadPort(0)
	check("read_port", err == nil && port <= 0xFF)
	check("write_port", chip.WritePort(0, 0x55) == nil)
	_ = chip.WritePort(0, 0x00)

	p7 := chip.Pin(7)
	check("pin7_output", p7.Mode(ioexpander.MCP23008Output) == nil)
	check("pin7_high", p7.Write(true) == nil)
	check("pin7_toggle", p7.Toggle() == nil)
	_ = p7.Mode(ioexpander.MCP23008Input)

	full, err := ioexpander.NewMCP23008Full(conn, ADDR)
	if err != nil {
		fmt.Printf("FAIL new_full: %v\n", err)
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed+1)
		os.Exit(1)
	}

	check("iodir_write", full.WriteRegister(0x00, 0xF0) == nil)
	iodir, _ := full.ReadRegister(0x00)
	check("iodir_readback", iodir == 0xF0)
	for _, pattern := range []uint8{0x05, 0x0A, 0x00} {
		_ = full.WritePort(0, pattern)
		v, _ := full.ReadPort(0)
		check(fmt.Sprintf("loopback_%#x", pattern), (v>>4)&0x0F == pattern)
	}

	_ = full.ConfigurePolarity(0xF0)
	v, _ := full.ReadPort(0)
	check("polarity_inverted", (v>>4)&0x0F == 0x0F)
	_ = full.ConfigurePolarity(0x00)

	_ = full.ConfigurePullup(0xF0)
	gppu, _ := full.ReadRegister(0x06)
	check("pullup_readback", gppu == 0xF0)
	_ = full.ConfigurePullup(0x00)

	_ = full.SetDefaultValue(0x00)
	_ = full.EnablePinInterrupt(0x10)
	gpinten, _ := full.ReadRegister(0x02)
	check("gpinten_readback", gpinten == 0x10)
	_ = full.WritePort(0, 0x01)
	flags, err := full.PollInterrupt()
	check("poll_interrupt", err == nil && flags <= 0xFF)
	_, err = full.ReadCapture()
	check("read_capture", err == nil)
	_ = full.EnablePinInterrupt(0x00)
	_ = full.WritePort(0, 0x00)

	check("reset", full.Reset() == nil)
	iodir, _ = full.ReadRegister(0x00)
	check("reset_iodir", iodir == 0xFF)

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

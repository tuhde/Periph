//go:build linux && !tinygo

// PCA6416A hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the PCA6416A check sequence. Prints
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

	chip, err := ioexpander.NewPCA6416AMinimal(conn, ADDR)
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

	// Wiring for the loopback checks: jumper port 0 to port 1 (P0_n <-> P1_n).
	port, err := chip.ReadPort(1)
	check("read_port", err == nil && port <= 0xFF)
	check("write_port", chip.WritePort(0, 0x55) == nil && chip.WritePort(1, 0xAA) == nil)
	_ = chip.WritePort(0, 0xFF)
	_ = chip.WritePort(1, 0xFF)

	p15 := chip.Pin(15)
	check("pin15_output", p15.Mode(ioexpander.PCA6416AOutput) == nil)
	check("pin15_high", p15.Write(true) == nil)
	check("pin15_toggle", p15.Toggle() == nil)
	check("pin15_pullup_rejected", p15.Mode(ioexpander.PCA6416AInputPullup) == ioexpander.ErrPCA6416ANoPullup)
	_ = p15.Mode(ioexpander.PCA6416AInput)

	full, err := ioexpander.NewPCA6416AFull(conn, ADDR)
	if err != nil {
		fmt.Printf("FAIL new_full: %v\n", err)
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed+1)
		os.Exit(1)
	}

	check("config_write", full.SetDirection(0xFF00) == nil)
	config0, _ := full.ReadRegister(0x06)
	config1, _ := full.ReadRegister(0x07)
	check("config_readback", config0 == 0x00 && config1 == 0xFF)
	for _, pattern := range []uint8{0x5A, 0xA5, 0x00} {
		_ = full.WritePort(0, pattern)
		v, _ := full.ReadPort(1)
		check(fmt.Sprintf("loopback_%#x", pattern), v == pattern)
	}
	_ = full.WriteAll(0x00FF)
	all, _ := full.ReadAll()
	check("read_all_pair", all == 0xFFFF)
	_ = full.WriteAll(0x0000)

	_ = full.ConfigurePolarity(0xFF00)
	pol, _ := full.ReadRegister(0x05)
	check("polarity1_readback", pol == 0xFF)
	v, _ := full.ReadPort(1)
	check("polarity_inverted", v == 0xFF)
	_ = full.ConfigurePolarity(0x0000)

	_, _ = full.PollInterrupt()
	_ = full.WritePort(0, 0x01)
	changed, err := full.PollInterrupt()
	check("poll_interrupt_changed", err == nil && changed == 0x0100)
	_ = full.WritePort(0, 0x00)

	check("reset", full.Reset() == nil)
	config0, _ = full.ReadRegister(0x06)
	config1, _ = full.ReadRegister(0x07)
	check("reset_config", config0 == 0xFF && config1 == 0xFF)
	out0, _ := full.ReadRegister(0x02)
	out1, _ := full.ReadRegister(0x03)
	check("reset_output", out0 == 0xFF && out1 == 0xFF)

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

//go:build tinygo

// PCA9539 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an PCA9539 on I2C1 (GP4 = SDA,
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
	const ADDR = uint8(0x74)

	conn := connection.NewI2CConnection(i2c, ADDR, nil, nil)
	chip, err := ioexpander.NewPCA9539Minimal(conn, ADDR)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
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
	check("pin15_output", p15.Mode(ioexpander.PCA9539Output) == nil)
	check("pin15_high", p15.Write(true) == nil)
	check("pin15_toggle", p15.Toggle() == nil)
	check("pin15_pullup_rejected", p15.Mode(ioexpander.PCA9539InputPullup) == ioexpander.ErrPCA9539NoPullup)
	_ = p15.Mode(ioexpander.PCA9539Input)

	full, err := ioexpander.NewPCA9539Full(conn, ADDR)
	if err != nil {
		fmt.Printf("FAIL new_full: %v\n", err)
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed+1)
		return
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
}

//go:build tinygo

// PCA9536 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an PCA9536 on I2C1 (GP4 = SDA,
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
	const ADDR = uint8(0x41)

	conn := connection.NewI2CConnection(i2c, ADDR, nil, nil)
	chip, err := ioexpander.NewPCA9536Minimal(conn, ADDR)
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

	// Wiring for the loopback checks: jumper P0–P1 to P2–P3 (Pn <-> Pn+2).
	port, err := chip.ReadPort(0)
	check("read_port", err == nil && port <= 0x0F)
	check("write_port", chip.WritePort(0, 0x05) == nil)
	_ = chip.WritePort(0, 0x0F)

	p3 := chip.Pin(3)
	check("pin3_output", p3.Mode(ioexpander.PCA9536Output) == nil)
	check("pin3_high", p3.Write(true) == nil)
	check("pin3_toggle", p3.Toggle() == nil)
	check("pin3_pullup_rejected", p3.Mode(ioexpander.PCA9536InputPullup) == ioexpander.ErrPCA9536NoPullup)
	_ = p3.Mode(ioexpander.PCA9536Input)

	full, err := ioexpander.NewPCA9536Full(conn, ADDR)
	if err != nil {
		fmt.Printf("FAIL new_full: %v\n", err)
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed+1)
		return
	}

	check("config_write", full.SetDirection(0x0C) == nil)
	config, _ := full.ReadRegister(0x03)
	check("config_readback", config&0x0F == 0x0C)
	for _, pattern := range []uint8{0x01, 0x02, 0x00} {
		_ = full.WritePort(0, pattern)
		v, _ := full.ReadPort(0)
		check(fmt.Sprintf("loopback_%#x", pattern), (v>>2)&0x03 == pattern)
	}

	_ = full.ConfigurePolarity(0x0C)
	pol, _ := full.ReadRegister(0x02)
	check("polarity_readback", pol&0x0F == 0x0C)
	v, _ := full.ReadPort(0)
	check("polarity_inverted", (v>>2)&0x03 == 0x03)
	_ = full.ConfigurePolarity(0x00)

	_, _ = full.PollChanges()
	_ = full.WritePort(0, 0x01)
	changed, err := full.PollChanges()
	check("poll_changes_changed", err == nil && changed == 0x04)
	_ = full.WritePort(0, 0x00)

	check("reset", full.Reset() == nil)
	config, _ = full.ReadRegister(0x03)
	check("reset_config", config&0x0F == 0x0F)
	out, _ := full.ReadRegister(0x01)
	check("reset_output", out&0x0F == 0x0F)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

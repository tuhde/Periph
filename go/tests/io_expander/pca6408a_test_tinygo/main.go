//go:build tinygo

// PCA6408A hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an PCA6408A on I2C1 (GP4 = SDA,
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
	const ADDR = uint8(0x20)

	conn := connection.NewI2CConnection(i2c, ADDR, nil, nil)
	chip, err := ioexpander.NewPCA6408AMinimal(conn, ADDR)
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

	// Wiring for the loopback checks: jumper P0–P3 to P4–P7 (Pn <-> Pn+4).
	port, err := chip.ReadPort(0)
	check("read_port", err == nil && port <= 0xFF)
	check("write_port", chip.WritePort(0, 0x55) == nil)
	_ = chip.WritePort(0, 0xFF)

	p7 := chip.Pin(7)
	check("pin7_output", p7.Mode(ioexpander.PCA6408AOutput) == nil)
	check("pin7_high", p7.Write(true) == nil)
	check("pin7_toggle", p7.Toggle() == nil)
	check("pin7_pullup_rejected", p7.Mode(ioexpander.PCA6408AInputPullup) == ioexpander.ErrPCA6408ANoPullup)
	_ = p7.Mode(ioexpander.PCA6408AInput)

	full, err := ioexpander.NewPCA6408AFull(conn, ADDR)
	if err != nil {
		fmt.Printf("FAIL new_full: %v\n", err)
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed+1)
		return
	}

	check("config_write", full.SetDirection(0xF0) == nil)
	config, _ := full.ReadRegister(0x03)
	check("config_readback", config == 0xF0)
	for _, pattern := range []uint8{0x05, 0x0A, 0x00} {
		_ = full.WritePort(0, pattern)
		v, _ := full.ReadPort(0)
		check(fmt.Sprintf("loopback_%#x", pattern), (v>>4)&0x0F == pattern)
	}

	_ = full.ConfigurePolarity(0xF0)
	pol, _ := full.ReadRegister(0x02)
	check("polarity_readback", pol == 0xF0)
	v, _ := full.ReadPort(0)
	check("polarity_inverted", (v>>4)&0x0F == 0x0F)
	_ = full.ConfigurePolarity(0x00)

	_, _ = full.PollInterrupt()
	_ = full.WritePort(0, 0x01)
	changed, err := full.PollInterrupt()
	check("poll_interrupt_changed", err == nil && changed == 0x10)
	_ = full.WritePort(0, 0x00)

	check("reset", full.Reset() == nil)
	config, _ = full.ReadRegister(0x03)
	check("reset_config", config == 0xFF)
	out, _ := full.ReadRegister(0x01)
	check("reset_output", out == 0xFF)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

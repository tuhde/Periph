//go:build tinygo

// PCA9539 complete example — TinyGo / Raspberry Pi Pico W. Exercises every method of the Full API.
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
		panic(err)
	}
	devAddr := uint8(0x74)

	conn := connection.NewI2CConnection(i2c, devAddr, nil, nil) // Create I2C connection, (i2c, addr=0x74, intPin=nil, enPin=nil) → *I2CConnection

	chip, err := ioexpander.NewPCA9539Full(conn, devAddr) // Create PCA9539 driver, (connection, addr=0x74) → (*PCA9539Full, error)
	// writes the reset values; all pins start as inputs
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get Full pin proxy, (n=0) → PCA9539FullPin
	// P00; all sixteen pins are bidirectional
	_ = p0.Mode(ioexpander.PCA9539Output) // Set pin direction, (m PinMode) → error
	// latches OUTPUT, then clears the CONFIG bit
	_ = p0.Write(false) // Write the output latch, (high bool) → error
	// clears the OUTPUT bit via the shadow
	_ = p0.Toggle() // Invert the output latch, () → error
	// one OUTPUT write, no read-modify-write
	level, _ := p0.Read() // Read actual level, () → (bool, error)
	// reads INPUT (actual pin level)
	fmt.Println("P0 level:", level)

	err = chip.Pin(5).Mode(ioexpander.PCA9539InputPullup) // Pull-up request, (m PinMode) → error
	// rejected with ErrPCA9539NoPullup: the chip has no pull-up resistors
	fmt.Println("pull-up request:", err)

	_ = chip.WritePort(0, 0x05) // Write port 0 output latch, (port, mask) → error
	// single-port OUTPUT write, updates the shadow
	_ = chip.WriteAll(0xA505) // Write both output latches, (value uint16) → error
	// one two-byte write at OUTPUT0 (register-pair toggling)
	port, _ := chip.ReadPort(1) // Read port 1 pins, (port) → (uint8, error)
	// INPUT1 register
	fmt.Printf("PORT1=0x%02X\n", port)
	all, _ := chip.ReadAll() // Read all 16 pins, () → (uint16, error)
	// one two-byte read at INPUT0
	fmt.Printf("ALL=0x%04X\n", all)

	_ = chip.ConfigurePolarity(0x0000) // Input polarity, (mask) → error
	// 1 bits invert the INPUT read
	_ = chip.SetDirection(0xFF00) // Whole-device direction, (mask) → error
	// port 0 outputs, port 1 inputs; OUTPUT is written first
	dir := chip.GetDirection() // Read the direction shadow, () → uint16
	// 1 = input, 0 = output
	fmt.Printf("CONFIG=0x%04X\n", dir)
	config, _ := chip.ReadRegister(0x07) // Read raw register, (reg) → (uint8, error)
	// escape hatch for debugging
	fmt.Printf("CONFIG1=0x%02X\n", config)
	_ = chip.WriteRegister(0x05, 0x10) // Write raw register, (reg, value) → error
	// POLARITY1: invert P14

	_ = chip.OnInterrupt(func(changed uint16) { // Subscribe to INT, (callback func(changed uint16)) → error
		fmt.Printf("interrupt, changed=0x%04X\n", changed)
	})
	// INT line if wired, else 5 ms polling
	_ = chip.Pin(12).Watch(connection.Change, func(p ioexpander.PCA9539FullPin) { // Watch P14, (trigger, handler) → error
		fmt.Println("P14 changed")
	})
	// edge filtered in software; all pins share the INT line
	changed, _ := chip.PollInterrupt() // Read INPUT0/1, get changed mask, () → (uint16, error)
	// XOR with the last INPUT values
	fmt.Printf("changed=0x%04X\n", changed)
	_ = chip.Pin(12).Unwatch() // Stop watching P14, () → error
	// drops the handler
	_ = chip.OffInterrupt() // Unsubscribe, () → error
	// stops delivery
	_ = chip.Reset() // Restore reset values, () → error
	// OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
}

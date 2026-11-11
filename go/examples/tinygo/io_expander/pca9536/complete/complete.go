//go:build tinygo

// PCA9536 complete example — TinyGo / Raspberry Pi Pico W. Exercises every method of the Full API.
package main

import (
	"fmt"
	"machine"
	"time"

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
	devAddr := uint8(0x41)

	conn := connection.NewI2CConnection(i2c, devAddr, nil, nil) // Create I2C connection, (i2c, addr=0x41, intPin=nil, enPin=nil) → *I2CConnection

	chip, err := ioexpander.NewPCA9536Full(conn, devAddr) // Create PCA9536 driver, (connection, addr=0x41) → (*PCA9536Full, error)
	// writes the reset values; all pins start as inputs
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get Full pin proxy, (n=0) → PCA9536FullPin
	// P0; all four pins are bidirectional
	_ = p0.Mode(ioexpander.PCA9536Output) // Set pin direction, (m PinMode) → error
	// latches OUTPUT, then clears the CONFIG bit
	_ = p0.Write(false) // Write the output latch, (high bool) → error
	// clears the OUTPUT bit via the shadow
	_ = p0.Toggle() // Invert the output latch, () → error
	// one OUTPUT write, no read-modify-write
	level, _ := p0.Read() // Read actual level, () → (bool, error)
	// reads INPUT (actual pin level)
	fmt.Println("P0 level:", level)

	err = chip.Pin(3).Mode(ioexpander.PCA9536InputPullup) // Pull-up request, (m PinMode) → error
	// rejected with ErrPCA9536NoPullup: the weak pull-up is fixed in hardware
	fmt.Println("pull-up request:", err)

	_ = chip.WritePort(0, 0x01) // Write the output latch, (port=0, mask) → error
	// whole-port OUTPUT write
	port, _ := chip.ReadPort(0) // Read all 4 pins, (port=0) → (uint8, error)
	// INPUT register
	fmt.Printf("PORT=0x%X\n", port)

	_ = chip.ConfigurePolarity(0x00) // Input polarity, (mask) → error
	// 1 bits invert the INPUT read
	_ = chip.SetDirection(0x0C) // Whole-port direction, (mask) → error
	// P0–P1 outputs, P2–P3 inputs; OUTPUT is written first
	dir := chip.GetDirection() // Read the direction shadow, () → uint8
	// 1 = input, 0 = output
	fmt.Printf("CONFIG=0x%X\n", dir)
	config, _ := chip.ReadRegister(0x03) // Read raw register, (reg) → (uint8, error)
	// escape hatch for debugging
	fmt.Printf("CONFIG=0x%X\n", config)
	_ = chip.WriteRegister(0x02, 0x04) // Write raw register, (reg, value) → error
	// POLARITY: invert P2

	_ = chip.WatchChanges(func(changed uint8) { // Start polling for changes, (callback func(changed uint8), interval) → error
		fmt.Printf("changed=0x%X\n", changed)
	}, 5*time.Millisecond)
	// no INT pin exists; a goroutine polls INPUT
	_ = chip.Pin(2).Watch(connection.Change, func(p ioexpander.PCA9536FullPin) { // Watch P2, (trigger, handler) → error
		fmt.Println("P2 changed")
	})
	// edge filtered in software; all pins share one poll
	changed, _ := chip.PollChanges() // Read INPUT, get changed mask, () → (uint8, error)
	// XOR with the last INPUT value
	fmt.Printf("changed=0x%X\n", changed)
	_ = chip.Pin(2).Unwatch() // Stop watching P2, () → error
	// drops the handler
	chip.UnwatchChanges() // Stop polling, ()
	// stops the goroutine
	_ = chip.Reset() // Restore reset values, () → error
	// OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
}

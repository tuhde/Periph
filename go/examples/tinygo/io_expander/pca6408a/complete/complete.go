//go:build tinygo

// PCA6408A complete example — TinyGo / Raspberry Pi Pico W. Exercises every method of the Full API.
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
	devAddr := uint8(0x20)

	conn := connection.NewI2CConnection(i2c, devAddr, nil, nil) // Create I2C connection, (i2c, addr=0x20, intPin=nil, enPin=nil) → *I2CConnection

	chip, err := ioexpander.NewPCA6408AFull(conn, devAddr) // Create PCA6408A driver, (connection, addr=0x20) → (*PCA6408AFull, error)
	// writes the reset values; all pins start as inputs
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get Full pin proxy, (n=0) → PCA6408AFullPin
	// P0; all eight pins are bidirectional
	_ = p0.Mode(ioexpander.PCA6408AOutput) // Set pin direction, (m PinMode) → error
	// latches OUTPUT, then clears the CONFIG bit
	_ = p0.Write(false) // Write the output latch, (high bool) → error
	// clears the OUTPUT bit via the shadow
	_ = p0.Toggle() // Invert the output latch, () → error
	// one OUTPUT write, no read-modify-write
	level, _ := p0.Read() // Read actual level, () → (bool, error)
	// reads INPUT (actual pin level)
	fmt.Println("P0 level:", level)

	err = chip.Pin(5).Mode(ioexpander.PCA6408AInputPullup) // Pull-up request, (m PinMode) → error
	// rejected with ErrPCA6408ANoPullup: the chip has no pull-up resistors
	fmt.Println("pull-up request:", err)

	_ = chip.WritePort(0, 0x05) // Write the output latch, (port=0, mask) → error
	// whole-port OUTPUT write
	port, _ := chip.ReadPort(0) // Read all 8 pins, (port=0) → (uint8, error)
	// INPUT register
	fmt.Printf("PORT=0x%02X\n", port)

	_ = chip.ConfigurePolarity(0x00) // Input polarity, (mask) → error
	// 1 bits invert the INPUT read
	_ = chip.SetDirection(0xF0) // Whole-port direction, (mask) → error
	// P0–P3 outputs, P4–P7 inputs; OUTPUT is written first
	dir := chip.GetDirection() // Read the direction shadow, () → uint8
	// 1 = input, 0 = output
	fmt.Printf("CONFIG=0x%02X\n", dir)
	config, _ := chip.ReadRegister(0x03) // Read raw register, (reg) → (uint8, error)
	// escape hatch for debugging
	fmt.Printf("CONFIG=0x%02X\n", config)
	_ = chip.WriteRegister(0x02, 0x10) // Write raw register, (reg, value) → error
	// POLARITY: invert P4

	_ = chip.OnInterrupt(func(changed uint8) { // Subscribe to INT, (callback func(changed uint8)) → error
		fmt.Printf("interrupt, changed=0x%02X\n", changed)
	})
	// INT line if wired, else 5 ms polling
	_ = chip.Pin(4).Watch(connection.Change, func(p ioexpander.PCA6408AFullPin) { // Watch P4, (trigger, handler) → error
		fmt.Println("P4 changed")
	})
	// edge filtered in software; all pins share the INT line
	changed, _ := chip.PollInterrupt() // Read INPUT, get changed mask, () → (uint8, error)
	// XOR with the last INPUT value
	fmt.Printf("changed=0x%02X\n", changed)
	_ = chip.Pin(4).Unwatch() // Stop watching P4, () → error
	// drops the handler
	_ = chip.OffInterrupt() // Unsubscribe, () → error
	// stops delivery
	_ = chip.Reset() // Restore reset values, () → error
	// OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
}

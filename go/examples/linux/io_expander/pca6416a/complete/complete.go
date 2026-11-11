//go:build linux && !tinygo

// PCA6416A complete example — Linux host. Exercises every method of the Full API.
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
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x20"), 0, 8)
	if err != nil {
		panic(err)
	}
	devAddr := uint8(addr)

	conn, err := connection.NewI2CConnection(bus, devAddr, nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := ioexpander.NewPCA6416AFull(conn, devAddr) // Create PCA6416A driver, (connection, addr=0x20) → (*PCA6416AFull, error)
	// writes the reset values; all pins start as inputs
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get Full pin proxy, (n=0) → PCA6416AFullPin
	// P0_0; all sixteen pins are bidirectional
	_ = p0.Mode(ioexpander.PCA6416AOutput) // Set pin direction, (m PinMode) → error
	// latches OUTPUT, then clears the CONFIG bit
	_ = p0.Write(false) // Write the output latch, (high bool) → error
	// clears the OUTPUT bit via the shadow
	_ = p0.Toggle() // Invert the output latch, () → error
	// one OUTPUT write, no read-modify-write
	level, _ := p0.Read() // Read actual level, () → (bool, error)
	// reads INPUT (actual pin level)
	fmt.Println("P0 level:", level)

	err = chip.Pin(5).Mode(ioexpander.PCA6416AInputPullup) // Pull-up request, (m PinMode) → error
	// rejected with ErrPCA6416ANoPullup: the chip has no pull-up resistors
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
	// POLARITY1: invert P1_4

	_ = chip.OnInterrupt(func(changed uint16) { // Subscribe to INT, (callback func(changed uint16)) → error
		fmt.Printf("interrupt, changed=0x%04X\n", changed)
	})
	// INT line if wired, else 5 ms polling
	_ = chip.Pin(12).Watch(connection.Change, func(p ioexpander.PCA6416AFullPin) { // Watch P1_4, (trigger, handler) → error
		fmt.Println("P1_4 changed")
	})
	// edge filtered in software; all pins share the INT line
	changed, _ := chip.PollInterrupt() // Read INPUT0/1, get changed mask, () → (uint16, error)
	// XOR with the last INPUT values
	fmt.Printf("changed=0x%04X\n", changed)
	_ = chip.Pin(12).Unwatch() // Stop watching P1_4, () → error
	// drops the handler
	_ = chip.OffInterrupt() // Unsubscribe, () → error
	// stops delivery
	_ = chip.Reset() // Restore reset values, () → error
	// OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// PCF8575 complete example — Linux host.
//
// Exercises every method in the PCF8575Full API: pin operations,
// per-port read/write, interrupt on-change detection (16-bit
// bitmask with bits 0–7 = Port 0, bits 8–15 = Port 1), and per-pin
// watch/unwatch.
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

	// --- PCF8575Minimal ---
	conn1, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn1.Close()

	chip, err := ioexpander.NewPCF8575Minimal(conn1, uint8(addr)) // Create PCF8575 minimal driver, (connection, addr) → (*PCF8575Minimal, error)
	if err != nil {
		panic(err)
	}
	// initialises all 16 pins as inputs; shadow = [0xFF, 0xFF]

	p0 := chip.Pin(0) // Get pin proxy, (n=0) → PCF8575Pin
	// port = n/8, bit = n%8; writes both shadow bytes

	p0.Set(true)  // Set high (quasi-input), (high=true) → error
	// shadow[0] |= (1 << 0); writes 2 bytes
	p0.Set(false) // Drive low, (high=false) → error
	// shadow[0] &^= (1 << 0); strong pull-down, ≤25 mA sink

	high, err := p0.Get() // Read actual level, () → (bool, error)
	// 2-byte read; returns bit for this pin
	if err != nil {
		panic(err)
	}
	fmt.Printf("P00 high=%v\n", high)

	port0, err := chip.ReadPort(0) // Read Port 0, (port=0) → (uint8, error)
	if err != nil {
		panic(err)
	}
	// bit n = actual level of pin P0n
	fmt.Printf("P0=0x%02X\n", port0)

	if err := chip.WritePort(0, 0b00001111); err != nil { // Write all 8 pins of port 0, (port=0, mask) → error
		panic(err)
	}
	// P00–P03 → output low; P04–P07 → input mode

	p10 := chip.Pin(8) // Get pin proxy, (n=8) → PCF8575Pin
	btn, err := p10.Get() // Read actual level, () → (bool, error)
	if err != nil {
		panic(err)
	}
	// 1 if P10 floating high; 0 if button pressed
	fmt.Printf("P10=%v\n", btn)

	if err := p0.Toggle(); err != nil { // Toggle shadow bit, () → error
		panic(err)
	}

	// --- PCF8575Full ---
	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn2.Close()

	full, err := ioexpander.NewPCF8575Full(conn2, uint8(addr)) // Create PCF8575 full driver, (connection, addr) → (*PCF8575Full, error)
	if err != nil {
		panic(err)
	}
	// stores initial port bytes for interrupt comparison

	changed, err := full.PollInterrupt() // Read both ports; return 16-bit changed bitmask, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	// bits 0–7 = Port 0 changed; bits 8–15 = Port 1 changed
	fmt.Printf("changed on init=0x%04X\n", changed)

	portA, err := full.ReadPort(0) // Read Port 0, (port=0) → (uint8, error)
	if err != nil {
		panic(err)
	}
	if err := full.WritePort(0, 0xFF); err != nil { // Write Port 0, (port=0, mask=0xFF) → error
		panic(err)
	}
	fmt.Printf("portA=0x%02X\n", portA)

	// --- Interrupt subscription and per-pin watch ---
	if err := full.OnInterrupt(func(mask uint16) { // Subscribe to INT, (callback) → error
		fmt.Printf("ports changed: 0x%04X\n", mask)
	}); err != nil {
		panic(err)
	}

	p9 := full.Pin(9) // Get full pin proxy, (n=9 → Port 1, bit 1) → PCF8575FullPin
	if err := p9.Watch(connection.Change, func(pin ioexpander.PCF8575FullPin) { // Subscribe to pin edges, (trigger, handler) → error
		fmt.Println("P11 changed")
	}); err != nil {
		panic(err)
	}
	_ = p9.Unwatch() // Unsubscribe pin handler, () → error

	if err := full.OffInterrupt(); err != nil { // Unsubscribe INT, () → error
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// PCF8574 complete example — Linux host.
//
// Exercises every method in the PCF8574Full API: shadow-aware pin
// operations, port-level read/write, interrupt on-change detection,
// and per-pin watch/unwatch.
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

	// --- PCF8574Minimal ---
	conn1, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn1.Close()

	chip, err := ioexpander.NewPCF8574Minimal(conn1, uint8(addr)) // Create PCF8574 minimal driver, (connection, addr) → (*PCF8574Minimal, error)
	if err != nil {
		panic(err)
	}
	// initialises all pins as inputs; shadow = 0xFF

	p0 := chip.Pin(0) // Get pin proxy, (n=0) → PCF8574Pin
	// holds plain pointer to chip; no bus transaction on construction

	p0.Set(true)  // Set high (quasi-input), (high=true) → error
	// shadow |= (1 << 0); writes shadow byte to bus
	p0.Set(false) // Drive low, (high=false) → error
	// shadow &^= (1 << 0); strong pull-down, ≤25 mA sink

	high, err := p0.Get() // Read actual level, () → (bool, error)
	// reads bus byte; returns (byte >> 0) & 1 == 1
	if err != nil {
		panic(err)
	}
	fmt.Printf("P0 high=%v\n", high)

	port, err := chip.ReadPort0() // Read all 8 pins, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	// bit n = actual level of pin Pn
	fmt.Printf("port=0x%02X\n", port)

	err = chip.WritePort0(0b00001111) // Write all 8 pins, (mask=0x0F) → error
	// P0–P3 → output low; P4–P7 → input mode
	if err != nil {
		panic(err)
	}

	p4 := chip.Pin(4) // Get pin proxy, (n=4) → PCF8574Pin
	btn, err := p4.Get() // Read actual level, () → (bool, error)
	if err != nil {
		panic(err)
	}
	// 1 if P4 floating high; 0 if button pressed
	fmt.Printf("P4=%v\n", btn)

	err = p0.Toggle() // Toggle shadow bit, () → error
	if err != nil {
		panic(err)
	}
	// inverts shadow bit and writes back

	// --- PCF8574Full ---
	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn2.Close()

	full, err := ioexpander.NewPCF8574Full(conn2, uint8(addr)) // Create PCF8574 full driver, (connection, addr) → (*PCF8574Full, error)
	if err != nil {
		panic(err)
	}
	// stores initial port byte for interrupt comparison

	changed, err := full.PollInterrupt() // Read port; return changed bitmask, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	// XOR of current vs previous read; clears INT line
	fmt.Printf("changed on init=0x%02X\n", changed)

	port2, err := full.ReadPort0() // Read all 8 pins, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	if err := full.WritePort0(0xFF); err != nil { // Write all 8 pins, (mask=0xFF) → error
		panic(err)
	}
	fmt.Printf("port2=0x%02X\n", port2)

	// --- Interrupt subscription and per-pin watch ---
	if err := full.OnInterrupt(func(mask uint8) { // Subscribe to INT, (callback) → error
		fmt.Printf("port changed: 0x%02X\n", mask)
	}); err != nil {
		panic(err)
	}

	p5 := full.Pin(5) // Get full pin proxy, (n=5) → PCF8574FullPin
	if err := p5.Watch(connection.Change, func(pin ioexpander.PCF8574FullPin) { // Subscribe to pin edges, (trigger, handler) → error
		fmt.Println("P5 changed")
	}); err != nil {
		panic(err)
	}
	_ = p5.Unwatch() // Unsubscribe pin handler, () → error

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

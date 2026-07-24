//go:build tinygo

// PCF8575 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises the full PCF8575 API: pin operations, per-port
// read/write, and interrupt on-change detection.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x20)              // Create I2C transport, (i2c, addr=0x20) → *I2CTransport
	chip, err := ioexpander.NewPCF8575Minimal(tr, 0x20)     // Create PCF8575 minimal driver, (transport, addr=0x20) → (*PCF8575Minimal, error)
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

	if err := chip.WritePort(0, 0b00001111); err != nil { // Write Port 0, (port=0, mask=0x0F) → error
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
	tr2 := transport.NewI2CTransport(i2c, 0x20)              // Create I2C transport, (i2c, addr=0x20) → *I2CTransport
	full, err := ioexpander.NewPCF8575Full(tr2, 0x20)        // Create PCF8575 full driver, (transport, addr=0x20) → (*PCF8575Full, error)
	if err != nil {
		panic(err)
	}
	// stores initial port bytes for interrupt comparison

	changed, err := full.ClearInterrupt() // Read both ports; return 16-bit changed bitmask, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	// bits 0–7 = Port 0 changed; bits 8–15 = Port 1 changed
	fmt.Printf("changed on init=0x%04X\n", changed)
}

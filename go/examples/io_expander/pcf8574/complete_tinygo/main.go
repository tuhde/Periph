//go:build tinygo

// PCF8574 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises the full PCF8574 API: pin operations, port-level
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
		Frequency: 100_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x20)            // Create I2C transport, (i2c, addr=0x20) → *I2CTransport
	chip, err := ioexpander.NewPCF8574Minimal(tr, 0x20)  // Create PCF8574 minimal driver, (transport, addr=0x20) → (*PCF8574Minimal, error)
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

	if err := chip.WritePort0(0b00001111); err != nil { // Write all 8 pins, (mask=0x0F) → error
		panic(err)
	}
	// P0–P3 → output low; P4–P7 → input mode

	p4 := chip.Pin(4) // Get pin proxy, (n=4) → PCF8574Pin
	btn, err := p4.Get() // Read actual level, () → (bool, error)
	if err != nil {
		panic(err)
	}
	// 1 if P4 floating high; 0 if button pressed
	fmt.Printf("P4=%v\n", btn)

	if err := p0.Toggle(); err != nil { // Toggle shadow bit, () → error
		panic(err)
	}
	// inverts shadow bit and writes back

	// --- PCF8574Full ---
	tr2 := transport.NewI2CTransport(i2c, 0x20)            // Create I2C transport, (i2c, addr=0x20) → *I2CTransport
	full, err := ioexpander.NewPCF8574Full(tr2, 0x20)      // Create PCF8574 full driver, (transport, addr=0x20) → (*PCF8574Full, error)
	if err != nil {
		panic(err)
	}
	// stores initial port byte for interrupt comparison

	changed, err := full.ClearInterrupt() // Read port; return changed bitmask, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	// XOR of current vs previous read; clears INT line
	fmt.Printf("changed on init=0x%02X\n", changed)
}

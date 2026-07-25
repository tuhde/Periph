//go:build linux && !tinygo

// MCP23017 complete example — Linux host.
//
// Exercises every method in the MCP23017Full API: pin operations,
// per-port read/write, direction configuration, pull-up enable,
// polarity inversion, and interrupt on-change.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/transport"
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

	// --- MCP23017Minimal ---
	tr1, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x20) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr1.Close()

	chip, err := ioexpander.NewMCP23017Minimal(tr1, uint8(addr)) // Create MCP23017 minimal driver, (transport, addr) → (*MCP23017Minimal, error)
	if err != nil {
		panic(err)
	}
	// clears OLAT, sets IODIR = 0x7F (pins 0–6 input, pin 7 output)

	p0 := chip.Pin(0) // Get pin proxy, (n=0) → MCP23017Pin
	// pin 0 → PORTA bit 0

	p0.Set(true)  // Drive high, (high=true) → error
	// shadow[0] |= (1 << 0); writes OLATA register
	p0.Set(false) // Drive low, (high=false) → error
	// shadow[0] &^= (1 << 0); strong drive both high and low

	high, err := p0.Get() // Read actual level, () → (bool, error)
	// reads GPIOA register
	if err != nil {
		panic(err)
	}
	fmt.Printf("PA0 high=%v\n", high)

	porta, err := chip.ReadPort(0) // Read PORTA, (port=0) → (uint8, error)
	if err != nil {
		panic(err)
	}
	// bit n = actual level of pin GPAn
	fmt.Printf("PORTA=0x%02X\n", porta)

	// Configure PORTA as all outputs (must keep GPA7 = output, bit 7 = 0)
	if err := chip.ConfigureDirection(0, 0x00); err != nil { // Configure direction A, (port=0, mask=0x00 → all output) → error
		panic(err)
	}

	if err := chip.WritePort(0, 0b00001111); err != nil { // Write PORTA via OLATA, (port=0, mask=0x0F) → error
		panic(err)
	}
	// PA0–PA3 high; PA4–PA6 high; PA7 high (output-only)

	if err := p0.Toggle(); err != nil { // Toggle shadow bit, () → error
		panic(err)
	}

	// --- MCP23017Full ---
	tr2, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x20) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr2.Close()

	full, err := ioexpander.NewMCP23017Full(tr2, uint8(addr)) // Create MCP23017 full driver, (transport, addr) → (*MCP23017Full, error)
	if err != nil {
		panic(err)
	}

	// Enable pull-ups on PORTB inputs (GPB0–GPB6)
	if err := full.ConfigurePullup(1, 0b01111111); err != nil { // Enable pull-ups, (port=1, mask) → error
		panic(err)
	}
	// GPPUA/B register; only electrically active on input pins

	if err := full.ConfigurePolarity(0, 0x0F); err != nil { // Configure polarity A, (port=0, mask=0x0F) → error
		panic(err)
	}
	// invert PA0–PA3 reads via IPOLA

	// Read interrupt flags without clearing
	flags, err := full.ReadInterruptFlags(0) // Read INTFA, (port=0) → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("INTFA=0x%02X\n", flags)

	// Read INTCAP to clear interrupt and return changed-pin bitmask
	changed, err := full.ClearInterrupt(0) // Read INTCAPA + GPIOA, return changed bitmask, (port=0) → (uint8, error)
	if err != nil {
		panic(err)
	}
	// bit n = 1 means pin n changed since last call; clears INTA
	fmt.Printf("changed=0x%02X\n", changed)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

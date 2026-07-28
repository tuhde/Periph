//go:build linux && !tinygo

// MCP23017 complete example — Linux host.
//
// Exercises every method in the MCP23017Full API: pin operations,
// per-port read/write, direction configuration, pull-up enable,
// polarity inversion, Level-3 interrupt on-change (per-port
// OnInterruptPort/OffInterruptPort, PollInterrupt, ReadCapture), and
// per-pin watch/unwatch.
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

	// --- MCP23017Minimal ---
	conn1, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn1.Close()

	chip, err := ioexpander.NewMCP23017Minimal(conn1, uint8(addr)) // Create MCP23017 minimal driver, (connection, addr) → (*MCP23017Minimal, error)
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
	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn2.Close()

	full, err := ioexpander.NewMCP23017Full(conn2, uint8(addr)) // Create MCP23017 full driver, (connection, addr) → (*MCP23017Full, error)
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

	// --- Interrupt subscription: single port ---
	if err := full.OnInterruptPort(0, func(status uint8) { // Subscribe to INT on one port, (port=0, callback) → error
		fmt.Printf("PORTA changed: 0x%02X\n", status)
	}); err != nil {
		panic(err)
	}
	// enables GPINTENA (all 8 pins) + INTCONA=0x00 (compare-to-previous)

	changed, err := full.PollInterrupt(0) // Read INTFA, return flags, and clear via INTCAPA, (port=0) → (uint8, error)
	if err != nil {
		panic(err)
	}
	// bit n = 1 means pin n triggered the interrupt
	fmt.Printf("changed on init=0x%02X\n", changed)

	captured, err := full.ReadCapture(0) // Read INTCAPA, (port=0) → (uint8, error)
	if err != nil {
		panic(err)
	}
	// captured PORTA state at moment of the most recent interrupt; also clears INTA
	fmt.Printf("captured PORTA=0x%02X\n", captured)

	// --- Per-pin watch ---
	p1 := full.Pin(1) // Get full pin proxy, (n=1 → PORTA bit 1) → MCP23017FullPin
	if err := p1.Watch(connection.Change, func(pin ioexpander.MCP23017FullPin) { // Subscribe to pin edges, (trigger, handler) → error
		fmt.Println("PA1 changed")
	}); err != nil {
		panic(err)
	}
	_ = p1.Unwatch() // Unsubscribe pin handler, () → error

	if err := full.OffInterruptPort(0); err != nil { // Disable + unsubscribe PORTA, (port=0) → error
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

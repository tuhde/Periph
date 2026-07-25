//go:build linux && !tinygo

// MCP23017 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N transport and reads a
// button on GPB0 to drive an LED on GPA0 every 200 ms.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

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

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x20) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := ioexpander.NewMCP23017Minimal(tr, uint8(addr)) // Create MCP23017 driver, (transport, addr=0x20) → (*MCP23017Minimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get pin proxy, (n=0 → PORTA bit 0) → MCP23017Pin
	p8 := chip.Pin(8) // Get pin proxy, (n=8 → PORTB bit 0) → MCP23017Pin

	p0.Set(false) // Drive low, (high=false) → error

	for {
		porta, err := chip.ReadPort(0) // Read PORTA, (port=0) → (uint8, error)
		if err != nil {
			panic(err)
		}
		portb, err := chip.ReadPort(1) // Read PORTB, (port=1) → (uint8, error)
		if err != nil {
			panic(err)
		}
		btn, err := p8.Get() // Read actual level, () → (bool, error)
		if err != nil {
			panic(err)
		}
		if btn {
			_ = p0.Set(true) // Drive high, (high=true) → error
		} else {
			_ = p0.Set(false) // Drive low, (high=false) → error
		}
		fmt.Printf("PORTA=0x%02X  PORTB=0x%02X  GPB0=%d\n", porta, portb, boolToInt(btn))
		time.Sleep(200 * time.Millisecond)
	}
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

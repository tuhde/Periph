//go:build tinygo

// MCP23017 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver with a configured I2C1 peripheral and reads
// a button on GPB0 to drive an LED on GPA0 every 200 ms.
package main

import (
	"fmt"
	"machine"
	"time"

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

	tr := transport.NewI2CTransport(i2c, 0x20)                // Create I2C transport, (i2c, addr=0x20) → *I2CTransport
	chip, err := ioexpander.NewMCP23017Minimal(tr, 0x20)      // Create MCP23017 driver, (transport, addr=0x20) → (*MCP23017Minimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get pin proxy, (n=0) → MCP23017Pin
	p8 := chip.Pin(8) // Get pin proxy, (n=8) → MCP23017Pin

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

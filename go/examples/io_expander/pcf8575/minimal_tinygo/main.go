//go:build tinygo

// PCF8575 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver with a configured I2C1 peripheral and reads
// a button on P10 to drive an LED on P00 every 200 ms.
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

	tr := transport.NewI2CTransport(i2c, 0x20)              // Create I2C transport, (i2c, addr=0x20) → *I2CTransport
	chip, err := ioexpander.NewPCF8575Minimal(tr, 0x20)     // Create PCF8575 driver, (transport, addr=0x20) → (*PCF8575Minimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get pin proxy, (n=0) → PCF8575Pin
	p8 := chip.Pin(8) // Get pin proxy, (n=8) → PCF8575Pin

	p0.Set(false) // Drive low, (high=false) → error

	for {
		port0, err := chip.ReadPort(0) // Read Port 0, (port=0) → (uint8, error)
		if err != nil {
			panic(err)
		}
		port1, err := chip.ReadPort(1) // Read Port 1, (port=1) → (uint8, error)
		if err != nil {
			panic(err)
		}
		btn, err := p8.Get() // Read actual level, () → (bool, error)
		if err != nil {
			panic(err)
		}
		if btn {
			_ = p0.Set(true) // Set high (quasi-input), (high=true) → error
		} else {
			_ = p0.Set(false) // Drive low, (high=false) → error
		}
		fmt.Printf("P0=0x%02X  P1=0x%02X  P10=%d\n", port0, port1, boolToInt(btn))
		time.Sleep(200 * time.Millisecond)
	}
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

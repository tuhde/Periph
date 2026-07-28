//go:build tinygo

// PCF8574 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver with a configured I2C1 peripheral and reads
// a button on P4 to drive an LED on P0 every 200 ms.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn := connection.NewI2CConnection(i2c, 0x20, nil, nil)  // Create I2C connection, (i2c, addr=0x20, intPin=nil, enPin=nil) → *I2CConnection
	chip, err := ioexpander.NewPCF8574Minimal(conn, 0x20)     // Create PCF8574 driver, (connection, addr=0x20) → (*PCF8574Minimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get pin proxy, (n=0) → PCF8574Pin
	p4 := chip.Pin(4) // Get pin proxy, (n=4) → PCF8574Pin

	p0.Set(false) // Drive low, (high=false) → error

	for {
		port, err := chip.ReadPort0() // Read all 8 pins, () → (uint8, error)
		if err != nil {
			panic(err)
		}
		btn, err := p4.Get() // Read actual level, () → (bool, error)
		if err != nil {
			panic(err)
		}
		if btn {
			_ = p0.Set(true) // Set high (quasi-input), (high=true) → error
		} else {
			_ = p0.Set(false) // Drive low, (high=false) → error
		}
		fmt.Printf("port=0x%02X  P4=%d\n", port, boolToInt(btn))
		time.Sleep(200 * time.Millisecond)
	}
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

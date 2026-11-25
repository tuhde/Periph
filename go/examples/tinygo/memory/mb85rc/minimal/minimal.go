//go:build tinygo

// MB85RC minimal example — TinyGo / Raspberry Pi Pico W.
//
// Uses machine.I2C1 (GP4 = SDA, GP5 = SCL); writes an incrementing byte to
// address 0 and reads it back in a loop.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn := connection.NewI2CConnectionWithWidth(i2c, 0x50, 2, nil, nil) // Create I2C connection, (i2c, addr=0x50, regBytes=2) → (*I2CConnection)

	chip, err := memory.NewMB85RCMinimal(conn) // Create MB85RC driver, (connection) → (*MB85RCMinimal, error)
	if err != nil {
		panic(err)
	}

	for counter := byte(0); ; counter++ {
		if err := chip.WriteUserByte(0x0000, counter); err != nil { // Write a single byte, (address 0–32767, value 0–255) → error
			println("write_byte:", err.Error())
		}
		v, err := chip.ReadUserByte(0x0000) // Read a single byte, (address 0–32767) → (byte, error)
		if err != nil {
			println("read_byte:", err.Error())
		} else {
			println("Stored:", v)
		}
		time.Sleep(time.Second)
	}
}

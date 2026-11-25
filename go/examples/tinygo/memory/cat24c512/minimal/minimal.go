//go:build tinygo

// CAT24C512 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then writes an incrementing byte to address 0 and reads it back once per second.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnectionWithWidth(i2c, 0x50, 2, nil, nil) // Create I2C connection, (i2c, addr=0x50, regBytes=2) → (*I2CConnection)

	chip, err := memory.NewCAT24C512Minimal(conn) // Create CAT24C512 driver, (connection) → (*CAT24C512Minimal, error)
	chk(err)

	counter := byte(0)
	for {
		chk(chip.WriteByteAt(0x0000, counter)) // Write a single byte, (address 0–65535, value 0–255) → error
		v, err := chip.ReadByteAt(0x0000)      // Read a single byte, (address 0–65535) → (byte, error)
		chk(err)
		fmt.Println("Stored:", v)
		counter++
		time.Sleep(time.Second)
	}
}

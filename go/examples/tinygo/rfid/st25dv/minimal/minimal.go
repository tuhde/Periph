//go:build tinygo

// ST25DV minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, writes "Hello NFC" to user memory and reads it back once per second.
// The ST25DV answers on two addresses: 0x53 (user memory, dynamic registers,
// mailbox) and 0x57 (system area), both with 16-bit register addresses.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
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

	user := connection.NewI2CConnectionWithWidth(i2c, 0x53, 2, nil, nil)   // Create user-area connection, (i2c, addr=0x53, regBytes=2) → (*I2CConnection)
	system := connection.NewI2CConnectionWithWidth(i2c, 0x57, 2, nil, nil) // Create system-area connection, (i2c, addr=0x57, regBytes=2) → (*I2CConnection)

	chip, err := rfid.NewST25DVMinimal(user, system, false) // Create ST25DV driver, (user, system, gpoActiveHigh=false) → (*ST25DVMinimal, error)
	chk(err)

	chk(chip.Write(0x0100, []byte("Hello NFC"))) // Write user memory, (address, data) → error
	for {
		b, err := chip.Read(0x0100, 9) // Read user memory, (address, length) → ([]byte, error)
		chk(err)
		fmt.Printf("Memory: %s\n", b)
		time.Sleep(time.Second)
	}
}

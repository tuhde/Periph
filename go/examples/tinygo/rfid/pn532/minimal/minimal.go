//go:build tinygo

// PN532 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then prints the UID of a card once per second.
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

	conn := connection.NewI2CConnection(i2c, 0x24, nil, nil) // Create I2C connection, (i2c, addr=0x24) → (*I2CConnection)

	chip, err := rfid.NewPN532Minimal(conn) // Create PN532 driver, (connection) → (*PN532Minimal, error)
	chk(err)
	chk(chip.Init()) // Wake, check firmware, SAM normal mode, finite retries, () → error

	for {
		uid, err := chip.ReadUID(time.Second) // Detect a type A card, (timeout=1 s) → ([]byte | nil, error)
		chk(err)
		fmt.Printf("UID %X\n", uid)
		time.Sleep(time.Second)
	}
}

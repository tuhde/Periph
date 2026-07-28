//go:build tinygo

// MFRC522 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver at the MFRC522's I2C (DDC) address 0x28, and
// polls for an ISO/IEC 14443 Type A card. When a card is detected,
// the UID is read and printed.
package main

import (
	"encoding/hex"
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
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

	conn := connection.NewI2CConnection(i2c, 0x28, nil, nil)         // Create I2C connection, (i2c, addr=0x28) → *I2CConnection
	chip, err := rfid.NewMFRC522Minimal(conn)            // Create MFRC522 driver, (connection) → (*MFRC522Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		present, err := chip.IsCardPresent() // Probe for a card, () → (bool, error)
		if err != nil {
			println("is_card_present:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		if !present {
			fmt.Println("waiting for card...")
			time.Sleep(500 * time.Millisecond)
			continue
		}
		uid, err := chip.ReadUID() // Read card UID, () → ([]byte, error)
		if err != nil {
			println("read_uid:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		if uid == nil {
			fmt.Println("no uid (card lost during select)")
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("card uid: %s\n", hex.EncodeToString(uid))
		time.Sleep(time.Second)
	}
}

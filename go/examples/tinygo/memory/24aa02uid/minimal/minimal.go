//go:build tinygo

// 24AA02UID minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and loops reading the 32-bit factory serial
// number.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
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

	tr := transport.NewI2CTransport(i2c, 0x50)                    // Create I2C transport, (i2c, addr=0x50) → (*I2CTransport)
	chip, err := memory.NewEEPROM24AA02UIDMinimal(tr)            // Create 24AA02UID driver, (transport) → (*EEPROM24AA02UIDMinimal, error)
	if err != nil {
		panic(err)
	}

	for {
		uid, err := chip.ReadUID() // Read 32-bit unique serial number, () → ([4]byte, error)
		if err != nil {
			println("read_uid:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("UID: %02X%02X%02X%02X\n", uid[0], uid[1], uid[2], uid[3])
		time.Sleep(2 * time.Second)
	}
}

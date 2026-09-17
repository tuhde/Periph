//go:build tinygo

// MPR121 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints the touch bitmask in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/other"
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

	conn := connection.NewI2CConnection(i2c, 0x5A, nil, nil) // Create I2C connection, (i2c, addr=0x5A) → (*I2CConnection)
	chip, err := other.NewMPR121Minimal(conn)                  // Create MPR121 driver, (connection) → (*MPR121Minimal, error)
	if err != nil {                                             // resets, applies default thresholds (T=12, R=6), enters Run Mode on all 12 electrodes
		panic(err)
	}

	for {
		t, err := chip.Touched() // Read 12-bit touch bitmask, () → (uint16 bitmask, error)
		if err != nil {
			fmt.Printf("touched: %v\n", err)
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("touched=0x%03X\n", t)
		time.Sleep(time.Second)
	}
}

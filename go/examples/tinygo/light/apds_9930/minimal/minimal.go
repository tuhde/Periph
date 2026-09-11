//go:build tinygo

// APDS-9930 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints lux + proximity in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
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

	conn := connection.NewI2CConnection(i2c, 0x39, nil, nil)        // Create I2C connection, (i2c, addr=0x39) → (*I2CConnection)
	chip, err := light.NewAPDS9930Minimal(conn) // Create APDS-9930 driver, (connection) → (*APDS9930Minimal, error)
	if err != nil {                              // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20
		panic(err)
	}

	for {
		lx, err := chip.Lux() // Read ambient illuminance, () → (float64 lx, error)
		if err != nil {        // IR-compensated lux via Ch0/Ch1 difference
			fmt.Printf("lux: %v\n", err)
			time.Sleep(time.Second)
			continue
		}
		p, err := chip.Proximity() // Read proximity count, () → (uint16 count, error)
		if err != nil {             // 16-bit ADC value; higher = closer object
			fmt.Printf("proximity: %v\n", err)
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("lux=%.1f lx  proximity=%d\n", lx, p)
		time.Sleep(time.Second)
	}
}
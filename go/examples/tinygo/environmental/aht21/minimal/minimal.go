//go:build tinygo

// AHT21 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints temperature and humidity in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
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

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil)         // Create I2C connection, (i2c, addr=0x38) → (*I2CConnection)
	chip, err := environmental.NewAHT21Minimal(conn)     // Create AHT21 driver, (connection) → (*AHT21Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, h, err := chip.Read() // Trigger measurement, () → (float32 °C, float32 %RH, error)
		if err != nil {
			println("read:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("T=%.2f C  H=%.2f %%RH\n", t, h)
		time.Sleep(time.Second)
	}
}

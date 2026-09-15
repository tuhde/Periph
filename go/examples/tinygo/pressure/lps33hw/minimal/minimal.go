//go:build tinygo

// LPS33HW minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints temperature and pressure in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil)   // Create I2C connection, (i2c, addr=0x5C) → (*I2CConnection)
	chip, err := pressure.NewLPS33HWMinimal(conn, 0x5C)        // Create LPS33HW driver, (connection, addr=0x5C) → (*LPS33HWMinimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			println("read temp:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		p, err := chip.Pressure() // Read pressure, () → (float32 Pa, error)
		if err != nil {
			println("read press:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("T=%.2f C  P=%.1f Pa\n", t, p)
		time.Sleep(time.Second)
	}
}
//go:build tinygo

// LPS28DFW minimal example — TinyGo / Raspberry Pi Pico W.
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

	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil)       // Create I2C connection, (i2c, addr=0x5C) → (*I2CConnection)
	chip, err := pressure.NewLPS28DFWMinimal(conn)              // Create LPS28DFW driver, (connection) → (*LPS28DFWMinimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, err := chip.ReadTemperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			println("read temp:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		p, err := chip.ReadPressure() // Read pressure, () → (float32 hPa, error)
		if err != nil {
			println("read press:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("T=%.2f C  P=%.2f hPa\n", t, p)
		time.Sleep(time.Second)
	}
}
//go:build tinygo

// BMP180 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints temperature and pressure in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	tr := transport.NewI2CTransport(i2c, 0x77)     // Create I2C transport, (i2c, addr=0x77) → (*I2CTransport)
	chip, err := pressure.NewBmp180Minimal(tr)     // Create BMP180 driver, (transport) → (*Bmp180Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, err := chip.Temperature() // Read temperature, () → (float64 C, error)
		if err != nil {
			println("temperature:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		p, err := chip.Pressure() // Read pressure, () → (float64 hPa, error)
		if err != nil {
			println("pressure:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("T=%.1f C, P=%.1f hPa\n", t, p)
		time.Sleep(time.Second)
	}
}

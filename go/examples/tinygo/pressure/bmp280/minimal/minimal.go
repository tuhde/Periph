//go:build tinygo

// BMP280 minimal example — TinyGo / Raspberry Pi Pico W.
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

	tr := transport.NewI2CTransport(i2c, 0x76)          // Create I2C transport, (i2c, addr=0x76) → (*I2CTransport)
	chip, err := pressure.NewBMP280Minimal(tr)         // Create BMP280 driver, (transport) → (*BMP280Minimal, error)
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
		p, err := chip.Pressure() // Read pressure, () → (float32 hPa, error)
		if err != nil {
			println("read press:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("T=%.2f C  P=%.2f hPa\n", t, p)
		time.Sleep(time.Second)
	}
}

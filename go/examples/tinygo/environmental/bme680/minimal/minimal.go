//go:build tinygo

// BME680 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints temperature, pressure, humidity, and
// gas resistance in a loop.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
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

	tr := transport.NewI2CTransport(i2c, 0x76)            // Create I2C transport, (i2c, addr=0x76) → (*I2CTransport)
	chip, err := environmental.NewBME680Minimal(tr)       // Create BME680 driver, (transport) → (*BME680Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			println("read temp:", err.Error())
			time.Sleep(5 * time.Second)
			continue
		}
		p, err := chip.Pressure() // Read pressure, () → (float32 hPa, error)
		if err != nil {
			println("read press:", err.Error())
			time.Sleep(5 * time.Second)
			continue
		}
		h, err := chip.Humidity() // Read humidity, () → (float32 %RH, error)
		if err != nil {
			println("read hum:", err.Error())
			time.Sleep(5 * time.Second)
			continue
		}
		g, err := chip.GasResistance() // Read gas resistance, () → (float32 Ω, error)
		if err != nil {
			println("read gas:", err.Error())
			time.Sleep(5 * time.Second)
			continue
		}
		if math.IsNaN(float64(g)) {
			fmt.Printf("T=%.1f C  P=%.1f hPa  H=%.1f %%RH  G=NaN\n", t, p, h)
		} else {
			fmt.Printf("T=%.1f C  P=%.1f hPa  H=%.1f %%RH  G=%.0f Ohm\n", t, p, h, g)
		}
		time.Sleep(5 * time.Second)
	}
}

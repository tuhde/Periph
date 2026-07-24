//go:build tinygo

// AHT21 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C0 on the Pico W's default I2C0 pins (GP4 = SDA,
// GP5 = SCL), constructs the driver, and prints temperature and humidity
// in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C0
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x38)         // Create I2C transport, (i2c, addr=0x38) → (*I2CTransport)
	chip, err := environmental.NewAHT21Minimal(tr)     // Create AHT21 driver, (transport) → (*AHT21Minimal, error)
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

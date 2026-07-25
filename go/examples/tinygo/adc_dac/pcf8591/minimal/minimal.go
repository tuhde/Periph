//go:build tinygo

// PCF8591 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and reads channel 0 + all four channels in a
// loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x48)              // Create I2C transport, (i2c, addr=0x48) → (*I2CTransport)
	chip, err := adcdac.NewPCF8591Minimal(tr)              // Create PCF8591 driver, (transport) → (*PCF8591Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		ch0, err := chip.ReadChannel(0) // Read single channel, (channel=0–3) → (uint8, error)
		if err != nil {
			println("read:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		all, err := chip.ReadAll() // Read all four channels, () → ([4]uint8, error)
		if err != nil {
			println("read_all:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("PCF8591 minimal running ch0=%d all=%v\n", ch0, all)
		time.Sleep(time.Second)
	}
}

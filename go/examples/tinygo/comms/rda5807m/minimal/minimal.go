//go:build tinygo

// RDA5807M minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and seeks to the next station in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
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

	tr := transport.NewI2CTransport(i2c, 0x10)             // Create I2C transport, (i2c, addr=0x10) → (*I2CTransport)
	fm, err := comms.NewRda5807mMinimal(tr, 100.0, 8)      // Create RDA5807M driver, (transport, frequency_mhz=100.0, volume=8) → (*Rda5807mMinimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if freq, err := fm.Seek(true); err != nil { // Seek to next station, (up=true) → (*float64 MHz, error)
			println("seek:", err.Error())
		} else if freq != nil {
			fmt.Printf("%.2f MHz\n", *freq)
		}
		time.Sleep(3 * time.Second)
	}
}

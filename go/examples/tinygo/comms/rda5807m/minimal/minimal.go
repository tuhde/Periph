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

	conn := connection.NewI2CConnection(i2c, 0x10, nil, nil)             // Create I2C connection, (i2c, addr=0x10) → (*I2CConnection)
	fm, err := comms.NewRDA5807MMinimal(conn, 100.0, 8)      // Create RDA5807M driver, (connection, frequency_mhz=100.0, volume=8) → (*RDA5807MMinimal, error)
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

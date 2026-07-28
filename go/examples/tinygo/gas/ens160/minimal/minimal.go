//go:build tinygo

// ENS160 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints AQI, TVOC, and eCO2 in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
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

	conn := connection.NewI2CConnection(i2c, 0x53, nil, nil)        // Create I2C connection, (i2c, addr=0x53) → (*I2CConnection)
	chip, err := gas.NewENS160Minimal(conn)             // Create ENS160 driver, (connection) → (*ENS160Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		aqi, tvoc, eco2, err := chip.ReadAirQuality() // Read air quality, () → (int, float32 ppb, float32 ppm, error)
		if err != nil {
			// Warm-up errors are common in the first minute after
			// power-on; just report and keep polling.
			fmt.Printf("read: %v\n", err)
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("AQI=%d  TVOC=%.0f ppb  eCO2=%.0f ppm\n", aqi, tvoc, eco2)
		time.Sleep(time.Second)
	}
}

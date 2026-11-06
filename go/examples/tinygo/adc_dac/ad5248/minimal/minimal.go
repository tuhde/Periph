//go:build tinygo

// AD5248 minimal — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
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
	conn := connection.NewI2CConnection(i2c, 0x2C, nil, nil) // Create I2C connection, (i2c, addr=0x2C) → (*I2CConnection)

	pot := adcdac.NewAD5248Minimal(conn, 10000, 160) // Create AD5248 driver, (connection, rAB=10000 Ω, rWiper=160 Ω) → *AD5248Minimal

	if err := pot.SetWiper(0, 64); err != nil { // Set channel 0 wiper, (channel 0-1, code 0-255) → error
		panic(err)
	}
	if err := pot.SetResistance(1, 2200); err != nil { // Set channel 1 W-B resistance, (channel 0-1, ohms) → error
		panic(err)
	}

	for {
		code, err := pot.GetWiper(0) // Read channel 0 wiper, (channel 0-1) → (uint8 0-255, error)
		if err != nil {
			panic(err)
		}
		ohms, err := pot.GetResistance(0) // Read channel 0 W-B resistance, (channel 0-1) → (float64 Ω, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("ch0 code=%d  R_WB=%.0f ohm\n", code, ohms)
		time.Sleep(time.Second)
	}
}

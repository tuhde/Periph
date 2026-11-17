//go:build tinygo

// LTC4151 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Reads supply voltage, current and power once per second. Uses machine.I2C1 with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
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

	conn := connection.NewI2CConnection(i2c, 0x6A, nil, nil) // Create I2C connection, (i2c, addr=0x6A) → (*I2CConnection)
	chip, err := power.NewLTC4151Minimal(conn, 0.02)         // Create LTC4151 driver, (connection, rSense=0.02 Ω) → (*LTC4151Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		v, err := chip.Voltage() // Read supply voltage, () → (float32 V, error)
		if err != nil {
			panic(err)
		}
		c, err := chip.Current() // Read load current, () → (float32 A, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Power() // Read load power, () → (float32 W, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("V=%.3f V  I=%.4f A  P=%.3f W\n", v, c, p)
		time.Sleep(time.Second)
	}
}

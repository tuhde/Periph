//go:build tinygo

// INA226 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints bus voltage, current, and power
// once per second.
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

	conn := connection.NewI2CConnection(i2c, 0x40, nil, nil)                  // Create I2C connection, (i2c, addr=0x40) → (*I2CConnection)
	chip, err := power.NewINA226Minimal(conn, 0.1, 2.0)            // Create INA226 driver, (connection, r_shunt=0.1 Ω, max_current=2.0 A) → (*INA226Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		v, err := chip.Voltage() // Read bus voltage, () → (float32 V, error)
		if err != nil {
			println("voltage:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		c, err := chip.Current() // Read load current, () → (float32 A, error)
		if err != nil {
			println("current:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		p, err := chip.Power() // Read load power, () → (float32 W, error)
		if err != nil {
			println("power:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("V=%.3f V  I=%.4f A  P=%.4f W\n", v, c, p)
		time.Sleep(time.Second)
	}
}

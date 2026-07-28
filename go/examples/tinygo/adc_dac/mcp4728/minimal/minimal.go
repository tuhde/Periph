//go:build tinygo

// MCP4728 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and exercises single-channel + set_all updates
// in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
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

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil)              // Create I2C connection, (i2c, addr=0x60) → (*I2CConnection)
	chip, err := adcdac.NewMCP4728Minimal(conn)               // Create MCP4728 driver, (connection) → (*MCP4728Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if err := chip.SetVoltage(0, 0.5); err != nil { // Set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → error
			println("set:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		if err := chip.SetRaw(1, 2048); err != nil { // Set channel B raw 12-bit code, (channel=0–3, code=0–4095) → error
			println("set:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		if err := chip.SetAll([4]float32{0.0, 0.25, 0.5, 1.0}); err != nil { // Update all four channels simultaneously, (fractions[4]) → error
			println("set_all:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Println("MCP4728 minimal running")
		time.Sleep(time.Second)
	}
}

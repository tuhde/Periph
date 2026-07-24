//go:build tinygo

// MCP4725 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and alternates the DAC between half-scale and
// three-quarter-scale.
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
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x60)              // Create I2C transport, (i2c, addr=0x60) → (*I2CTransport)
	chip, err := adcdac.NewMCP4725Minimal(tr)               // Create MCP4725 driver, (transport) → (*MCP4725Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if err := chip.SetVoltage(0.5); err != nil { // Set output as fraction of V_DD, (fraction=0.0–1.0) → error
			println("set:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Println("set 0.5")
		time.Sleep(time.Second)
		if err := chip.SetRaw(2048); err != nil { // Set raw 12-bit code, (code=0–4095) → error
			println("set:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Println("set raw 2048")
		time.Sleep(time.Second)
	}
}

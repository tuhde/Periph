//go:build tinygo

// MCP4XXX minimal — TinyGo / Raspberry Pi Pico W.
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

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

func main() {
	const part = "MCP4532"
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}
	defAddr, err := adcdac.MCP4XXXDefaultAddress(part)
	if err != nil {
		panic(err)
	}
	conn := connection.NewI2CConnection(i2c, defAddr, nil, nil) // Create I2C connection, (i2c, addr) → (*I2CConnection)

	pot, err := adcdac.NewMCP4XXXMinimal(conn, part, 10000, 75) // Create MCP4XXX driver, (connection, part="MCP4532", rAB=10000 Ω, rWiper=75 Ω) → (*MCP4XXXMinimal, error)
	if err != nil {
		panic(err)
	}
	if err := pot.Init(); err != nil { // Bus check, () → error
		panic(err)
	}

	if err := pot.SetWiper(32, 0); err != nil { // Set wiper, (code 0-128, channel=0) → error
		panic(err)
	}
	if err := pot.SetResistance(2200, 0); err != nil { // Set W-B resistance, (ohms, channel=0) → error
		panic(err)
	}

	for {
		code := must(pot.GetWiper(0))      // Read wiper, (channel=0) → (int 0-128, error)
		ohms := must(pot.GetResistance(0)) // Read W-B resistance, (channel=0) → (float64 Ω, error)
		fmt.Printf("code=%d  R_WB=%.0f ohm\n", code, ohms)
		time.Sleep(time.Second)
	}
}

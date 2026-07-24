//go:build tinygo

// MCP4725 demo example — TinyGo / Raspberry Pi Pico W.
//
// Triangle wave: ramp the DAC output from 0 to full scale and back down
// in 21 steps per direction, with a 100 ms pause between steps. The
// sequence produces a sawtooth on an oscilloscope and illustrates the
// resolution available from a 12-bit DAC.
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

	tr := transport.NewI2CTransport(i2c, 0x60)            // Create I2C transport, (i2c, addr=0x60) → (*I2CTransport)
	chip, err := adcdac.NewMCP4725Full(tr)                // Create MCP4725 driver, (transport) → (*MCP4725Full, error)
	if err != nil {
		panic(err)
	}

	const step float32 = 1.0 / 20.0

	fmt.Println("MCP4725 demo: triangle wave")
	// --- Up sweep: 0 to full scale in 21 steps ---
	// Each step is 1/20 of full scale so the sawtooth has 21 distinct
	// levels on the rising edge and 21 on the falling edge.
	for n := 0; n <= 20; n++ {
		fraction := float32(n) * step
		if err := chip.SetVoltage(fraction); err != nil { // Set output as fraction of V_DD, (fraction=0.0–1.0) → error
			panic(err)
		}
		code := uint16(float32(fraction) * 4095.0)
		approxV := float32(code) * 3.3 / 4096.0
		fmt.Printf("n=%2d fraction=%.2f code=%4d approx_v=%.3fV\n", n, fraction, code, approxV)
		time.Sleep(100 * time.Millisecond)
	}
	// --- Down sweep: full scale back to 0 in 20 steps ---
	// Walking the reverse direction completes one triangle cycle.
	for n := 20; n >= 0; n-- {
		fraction := float32(n) * step
		if err := chip.SetVoltage(fraction); err != nil { // Set output as fraction of V_DD, (fraction=0.0–1.0) → error
			panic(err)
		}
		code := uint16(float32(fraction) * 4095.0)
		approxV := float32(code) * 3.3 / 4096.0
		fmt.Printf("n=%2d fraction=%.2f code=%4d approx_v=%.3fV\n", n, fraction, code, approxV)
		time.Sleep(100 * time.Millisecond)
	}
	fmt.Println("Demo complete")
}

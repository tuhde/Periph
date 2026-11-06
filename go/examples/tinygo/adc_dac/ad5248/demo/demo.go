//go:build tinygo

// AD5248 demo — TinyGo / Raspberry Pi Pico W.
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

	pot := adcdac.NewAD5248Full(conn, 10000, 160) // Create AD5248 driver, (connection, rAB=10000 Ω, rWiper=160 Ω) → *AD5248Full

	// --- Sweep channel 0 across its full range ---
	// Programmable gain / level trim: step the wiper from code 0 to 255 in steps of 16
	// and print the resulting W-B resistance. Zero scale is not 0 Ω (about 2*RW = 320 Ω).
	for code := 0; code <= 255; code += 16 {
		if err := pot.SetWiper(0, code); err != nil { // Set channel 0 wiper, (channel 0-1, code 0-255) → error
			panic(err)
		}
		ohms, err := pot.GetResistance(0) // Read W-B resistance, (channel 0-1) → (float64 Ω, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("code=%3d  R_WB=%7.1f ohm\n", code, ohms)
		time.Sleep(100 * time.Millisecond)
	}

	// --- Set channel 1 to 2.2 kΩ and report the quantisation error ---
	// 256 positions over 10 kΩ give a step of about 39 Ω, so the result is the nearest code.
	if err := pot.SetResistance(1, 2200); err != nil { // Set channel 1 to 2.2 kΩ, (channel 0-1, ohms) → error
		panic(err)
	}
	actual, err := pot.GetResistance(1) // Read back W-B resistance, (channel 0-1) → (float64 Ω, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("ch1 target=2200 ohm  actual=%.1f ohm  error=%.1f ohm\n", actual, actual-2200)

	// --- Shutdown and resume channel 1 ---
	// Shutdown opens terminal A and shorts the wiper to B (about 0 Ω) without changing the RDAC.
	if err := pot.Shutdown(1); err != nil { // Shut channel 1 down, (channel 0-1) → error
		panic(err)
	}
	down, _ := pot.IsShutdown(1) // Query driver-tracked state, (channel 0-1) → (bool, error)
	code, _ := pot.GetWiper(1)
	fmt.Printf("ch1 shutdown=%v  code=%d\n", down, code)
	time.Sleep(time.Second)
	if err := pot.Resume(1); err != nil { // Resume channel 1, (channel 0-1) → error
		panic(err)
	}

	// --- Restore the power-up preset ---
	if err := pot.Reset(); err != nil { // Reset both channels to midscale, () → error
		panic(err)
	}
	c0, _ := pot.GetWiper(0)
	c1, _ := pot.GetWiper(1)
	fmt.Printf("after reset: ch0=%d ch1=%d\n", c0, c1)
}

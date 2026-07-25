//go:build linux && !tinygo

// MCP4725 demo example — Linux host.
//
// Triangle wave: ramp the DAC output from 0 to full scale and back down
// in 21 steps per direction, with a 100 ms pause between steps. The
// sequence produces a sawtooth on an oscilloscope and illustrates the
// resolution available from a 12-bit DAC.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x60) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := adcdac.NewMCP4725Full(tr) // Create MCP4725 driver, (transport) → (*MCP4725Full, error)
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

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

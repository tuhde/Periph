//go:build linux && !tinygo

// MCP4728 demo example — Linux host.
//
// Calibration use case: set all four channels to distinct voltages
// spanning the full scale, then sweep them together with set_all to
// illustrate synchronous multi-channel update.
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

	chip, err := adcdac.NewMCP4728Full(tr) // Create MCP4728 driver, (transport) → (*MCP4728Full, error)
	if err != nil {
		panic(err)
	}

	// --- Calibration points: A=0, B=1/3, C=2/3, D=full scale ---
	// A four-point calibration across the four channels. Each channel
	// receives a different target voltage computed from its fraction.
	targets := [4]float32{0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0}
	for ch := 0; ch < 4; ch++ {
		if err := chip.SetVoltage(uint8(ch), targets[ch]); err != nil { // Set channel as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → error
			panic(err)
		}
		code := uint16(targets[ch] * 4095.0)
		approxV := float32(code) * 3.3 / 4096.0
		fmt.Printf("ch%d target=%.4f code=%d approx_v=%.3fV\n", ch, targets[ch], code, approxV)
	}

	// --- Synchronous sweep: 16 steps from 0 to full scale ---
	// set_all updates channels A→D together in a single I²C transaction.
	// 50 ms between steps makes the sweep visible on a scope with all
	// four channels driven simultaneously.
	fmt.Println("MCP4728 demo: synchronous sweep")
	for n := 0; n <= 16; n++ {
		f := float32(n) / 16.0
		if err := chip.SetAll([4]float32{f, f, f, f}); err != nil { // Update all four channels simultaneously, (fractions[4]) → error
			panic(err)
		}
		fmt.Printf("n=%2d fraction=%.4f\n", n, f)
		time.Sleep(50 * time.Millisecond)
	}
	fmt.Println("Demo complete")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

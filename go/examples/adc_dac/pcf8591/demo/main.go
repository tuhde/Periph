//go:build linux && !tinygo

// PCF8591 demo example — Linux host.
//
// ADC→DAC feedback: read AIN0 (e.g. a potentiometer wiper) and write
// the same value to the on-chip DAC so that an LED on AOUT tracks the
// potentiometer. Assumes 3.3 V supply.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x48"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x48) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := adcdac.NewPCF8591Full(tr) // Create PCF8591 driver, (transport) → (*PCF8591Full, error)
	if err != nil {
		panic(err)
	}

	const vref float32 = 3.3
	const vagnd float32 = 0.0

	// --- Single-ended mode with the DAC output enabled ---
	// AOE=1 keeps the internal oscillator running; this is required
	// for reliable conversions when auto-increment is used, and lets
	// the DAC update AOUT in the same loop.
	if err := chip.Configure(adcdac.PCF8591Mode4SingleEnded, false, true); err != nil { // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → error
		panic(err)
	}

	// --- ADC→DAC feedback: AIN0 (pot wiper) drives AOUT (LED) ---
	// The raw 8-bit ADC code is mirrored to the DAC register so an LED
	// on AOUT tracks the potentiometer. Both voltages are computed
	// using V_AGND + raw × (V_REF − V_AGND) / 256.
	for n := 0; n < 20; n++ {
		raw, err := chip.ReadChannel(0) // Read single channel, (channel=0–3) → (uint8, error)
		if err != nil {
			panic(err)
		}
		vin := vagnd + float32(raw)*(vref-vagnd)/256.0
		if err := chip.SetDAC(raw); err != nil { // Enable DAC and set raw value, (value=0–255) → error
			panic(err)
		}
		vout := vagnd + float32(raw)*(vref-vagnd)/256.0
		fmt.Printf("n=%2d raw=%3d vin=%.3fV  vout=%.3fV\n", n, raw, vin, vout)
		time.Sleep(200 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

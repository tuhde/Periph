//go:build tinygo

// INA226 demo example — TinyGo / Raspberry Pi Pico W.
//
// A power-supply sanity check: poll the INA226 once per second for 10
// seconds, printing bus voltage, current, and power. The demo arms
// bus-voltage window alerts so an out-of-range reading is flagged.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
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

	tr := transport.NewI2CTransport(i2c, 0x40)                  // Create I2C transport, (i2c, addr=0x40) → (*I2CTransport)
	chip, err := power.NewINA226Full(tr, 0.1, 2.0)              // Create INA226 driver, (transport, r_shunt=0.1 Ω, max_current=2.0 A) → (*INA226Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure for noise-sensitive power-rail monitoring ---
	// 16-sample averaging suppresses switching noise on a noisy 5 V rail;
	// continuous mode avoids re-triggering overhead between measurements.
	if err := chip.Configure(3, 4, 4, 7); err != nil { // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → error
		panic(err)
	}

	// --- Window alert: 4.5 V < V_bus < 5.5 V expected (USB rail) ---
	// Reading the chip is enough to get V/I/P, but arming the alert
	// surfaces over/under-voltage events without continuous polling.
	if err := chip.SetAlert(power.BOL, 5.5, false, false); err != nil { // Set bus over-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	if err := chip.SetAlert(power.BUL, 4.5, false, false); err != nil { // Set bus under-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}

	for n := 0; n < 10; n++ {
		// --- Each iteration waits for the next conversion result ---
		// CVRF clears on read; if a fresh sample is not yet ready, the
		// call returns false and we sleep briefly before retrying.
		for {
			ready, err := chip.ConversionReady() // Check conversion done, () → (bool, error)
			if err != nil {
				panic(err)
			}
			if ready {
				break
			}
			time.Sleep(10 * time.Millisecond)
		}

		v, err := chip.Voltage() // Read bus voltage, () → (float32 V, error)
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
		fmt.Printf("V=%.3f V  I=%.4f A  P=%.4f W\n", v, c, p) // Read primary values, () → ...

		// --- Surface overflow conditions to the operator ---
		// OVF means the current/power calculation exceeded the
		// representable range — increase max_current or use a smaller
		// shunt to bring the readings back into range.
		ovf, err := chip.Overflow() // Check math overflow, () → (bool, error)
		if err != nil {
			panic(err)
		}
		if ovf {
			fmt.Printf("WARNING: math overflow at sample %d — increase max_current or reduce shunt\n", n)
		}

		time.Sleep(time.Second)
	}
}

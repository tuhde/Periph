//go:build tinygo

// INA219 demo example — TinyGo / Raspberry Pi Pico W.
//
// A power-supply sanity check: poll the INA219 once per second for 10
// seconds, printing bus voltage, current, and power. The demo uses
// triggered mode to take single-shot readings, exercising the
// trigger() path that the minimal example skips.
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
	chip, err := power.NewINA219Full(conn, 0.1, 2.0)              // Create INA219 driver, (connection, r_shunt=0.1 Ω, max_current=2.0 A) → (*INA219Full, error)
	if err != nil {
		panic(err)
	}

	// --- Use triggered mode so each iteration produces a fresh sample ---
	// Triggered mode avoids reading stale data when the loop is paced
	// slower than the chip's continuous conversion rate.
	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_BUS_TRIG); err != nil { // Configure chip, (brng, pga, badc, sadc, mode) → error
		panic(err)
	}

	for n := 0; n < 10; n++ {
		// --- Kick off a fresh single-shot conversion each loop ---
		// Writing the same triggered mode value again restarts a new
		// conversion, even if MODE already held that value.
		if err := chip.Trigger(); err != nil { // Re-trigger conversion, () → error
			panic(err)
		}
		// wait for the conversion to complete
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
		// representable range; either increase max_current or use a
		// smaller shunt to bring the readings back into range.
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

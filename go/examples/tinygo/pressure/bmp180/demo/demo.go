//go:build tinygo

// BMP180 demo example — TinyGo / Raspberry Pi Pico W.
//
// Pocket altimeter / weather logger. Reads temperature, pressure, and
// altitude once per second. The first reading is used as the sea-level
// reference, so altitude starts at exactly 0 m and any subsequent change
// is relative.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	tr := transport.NewI2CTransport(i2c, 0x77) // Create I2C transport, (i2c, addr=0x77) → (*I2CTransport)

	chip, err := pressure.NewBmp180Full(tr) // Create BMP180 driver, (transport) → (*Bmp180Full, error)
	if err != nil {
		panic(err)
	}
	chip.SetOversampling(pressure.OssUlp) // Set OSS, (oss 0–3) → error

	t0, err := chip.Temperature() // Read temperature, () → (float64 C, error)
	if err != nil {
		panic(err)
	}
	p0, err := chip.Pressure() // Read pressure, () → (float64 hPa, error)
	if err != nil {
		panic(err)
	}
	altRef, err := chip.AltitudeAt(1013.25) // Compute altitude, (sea_level_hpa=1013.25) → (float64 m, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Reference: %.1f C, %.1f hPa, alt=%.1f m\n", t0, p0, altRef)
	prevAlt := 0.0

	var minT, maxT, sumT, minP, maxP, sumP, minA, maxA, sumA float64
	minT, maxT, minP, maxP, minA, maxA = 1e9, -1e9, 1e9, -1e9, 1e9, -1e9

	for n := 0; n < 60; n++ {
		t, err := chip.Temperature() // Read temperature, () → (float64 C, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Pressure() // Read pressure, () → (float64 hPa, error)
		if err != nil {
			panic(err)
		}
		a, err := chip.AltitudeAt(1013.25) // Compute altitude, (sea_level_hpa=1013.25) → (float64 m, error)
		if err != nil {
			panic(err)
		}
		da := (a - prevAlt) * 100.0

		if t < minT {
			minT = t
		}
		if t > maxT {
			maxT = t
		}
		sumT += t
		if p < minP {
			minP = p
		}
		if p > maxP {
			maxP = p
		}
		sumP += p
		if a < minA {
			minA = a
		}
		if a > maxA {
			maxA = a
		}
		sumA += a

		if n > 0 {
			fmt.Printf("%ds: %.1f C, %.1f hPa, alt=%.1f m (delta=%+.0f cm)\n", n, t, p, a, da)
		} else {
			fmt.Printf("%ds: %.1f C, %.1f hPa, alt=%.1f m\n", n, t, p, a)
		}
		prevAlt = a
		time.Sleep(time.Second)
	}
	fmt.Printf("\nmin/max/mean over 60 samples:\n")
	fmt.Printf("  T:   %.1f / %.1f / %.1f C\n", minT, maxT, sumT/60.0)
	fmt.Printf("  P:   %.1f / %.1f / %.1f hPa\n", minP, maxP, sumP/60.0)
	fmt.Printf("  alt: %.1f / %.1f / %.1f m\n", minA, maxA, sumA/60.0)
}

//go:build tinygo

// BMP085 demo example — TinyGo (Pico W).
//
// Pocket altimeter / weather logger. Reads temperature, pressure, and
// altitude once per second. The first reading is used as the sea-level
// reference, so altitude starts at exactly 0 m and any subsequent change
// is relative.
package main

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
	"machine"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{
		Frequency: 100_000,
		SDA:       machine.GPIO4,
		SCL:       machine.GPIO5,
	})
	conn, err := connection.NewI2CConnection(machine.I2C0, 0x77, nil, nil) // Create I2C connection, (bus=I2C0, addr=0x77) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewBmp085Full(conn) // Create BMP085 driver, (connection) → (*Bmp085Full, error)
	if err != nil {
		panic(err)
	}
	chip.SetOversampling(pressure.OssUlp) // Set OSS, (oss 0–3) → error

	t0, err := chip.Temperature() // Read temperature, () → (float64 C, error)
	if err != nil {
		panic(err)
	}
	p0, err := chip.Pressure() // Read pressure, () → (float64 Pa, error)
	if err != nil {
		panic(err)
	}
	altRef, err := chip.AltitudeAt(101325.0) // Compute altitude, (sea_level_pa=101325.0) → (float64 m, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Reference: %.1f C, %.1f Pa, alt=%.1f m\n", t0, p0, altRef)
	prevAlt := 0.0

	var minT, maxT, sumT, minP, maxP, sumP, minA, maxA, sumA float64
	minT, maxT, minP, maxP, minA, maxA = 1e9, -1e9, 1e9, -1e9, 1e9, -1e9

	for n := 0; n < 60; n++ {
		t, err := chip.Temperature() // Read temperature, () → (float64 C, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Pressure() // Read pressure, () → (float64 Pa, error)
		if err != nil {
			panic(err)
		}
		a, err := chip.AltitudeAt(101325.0) // Compute altitude, (sea_level_pa=101325.0) → (float64 m, error)
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
			fmt.Printf("%ds: %.1f C, %.1f Pa, alt=%.1f m (delta=%+.0f cm)\n", n, t, p, a, da)
		} else {
			fmt.Printf("%ds: %.1f C, %.1f Pa, alt=%.1f m\n", n, t, p, a)
		}
		prevAlt = a
		time.Sleep(time.Second)
	}
	fmt.Printf("\nmin/max/mean over 60 samples:\n")
	fmt.Printf("  T:   %.1f / %.1f / %.1f C\n", minT, maxT, sumT/60.0)
	fmt.Printf("  P:   %.1f / %.1f / %.1f Pa\n", minP, maxP, sumP/60.0)
	fmt.Printf("  alt: %.1f / %.1f / %.1f m\n", minA, maxA, sumA/60.0)
}
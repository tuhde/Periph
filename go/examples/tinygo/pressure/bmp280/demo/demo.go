//go:build tinygo

// BMP280 demo example — TinyGo / Raspberry Pi Pico W.
//
// Weather-vs-altimeter switcher. The first 30 seconds run in weather
// monitoring preset (forced mode, ×1/×1, filter off, 1 Hz): print
// temperature, pressure, computed altitude. After 30 s, reconfigure to
// indoor navigation preset (normal mode, ×16/×2, filter coefficient 16,
// t_sb = 0.5 ms → ~26 Hz output) and poll as fast as practical for
// another 30 seconds. The reader sees the noise floor drop from ~50 cm
// (no filter) to a few cm (filter coefficient 16). After the run, prints
// min / max / std of the second-half altitude readings.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	conn := connection.NewI2CConnection(i2c, 0x76, nil, nil) // Create I2C connection, (i2c, addr=0x76) → (*I2CConnection)

	chip, err := pressure.NewBMP280Full(conn) // Create BMP280 driver, (connection) → (*BMP280Full, error)
	if err != nil {
		panic(err)
	}

	// --- Weather monitoring preset: lowest power, forced mode ---
	// BMP280 datasheet Table 7: ×1/×1, filter off, forced mode.
	// One sample per second for 30 seconds.
	chip.Configure( // Configure chip, (osrs_t=×1, osrs_p=×1, mode=forced, filter=off, t_sb=0) → error
		pressure.BMP280OSRSX1,
		pressure.BMP280OSRSX1,
		pressure.BMP280ModeForced,
		pressure.BMP280FilterOff,
		pressure.BMP280TSB0p5MS,
	)

	var tMin, tMax, tSum float32
	tMin, tMax = math.MaxFloat32, -math.MaxFloat32
	var pMin, pMax, pSum float32
	pMin, pMax = math.MaxFloat32, -math.MaxFloat32
	var aMin, aMax, aSum float32
	aMin, aMax = math.MaxFloat32, -math.MaxFloat32

	for n := 0; n < 30; n++ {
		t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Pressure() // Read pressure, () → (float32 hPa, error)
		if err != nil {
			panic(err)
		}
		a, err := chip.Altitude(1013.25) // Compute altitude, (sea_level_hpa=1013.25) → (float32 m, error)
		if err != nil {
			panic(err)
		}
		if t < tMin {
			tMin = t
		}
		if t > tMax {
			tMax = t
		}
		tSum += t
		if p < pMin {
			pMin = p
		}
		if p > pMax {
			pMax = p
		}
		pSum += p
		if a < aMin {
			aMin = a
		}
		if a > aMax {
			aMax = a
		}
		aSum += a
		fmt.Printf("%2ds: %.1f C, %.1f hPa, alt=%.1f m\n", n, t, p, a)
		time.Sleep(time.Second)
	}

	fmt.Printf("Weather: T=%.1f/%.1f/%.1f C, P=%.1f/%.1f/%.1f hPa, alt=%.1f/%.1f/%.1f m\n",
		tMin, tSum/30, tMax, pMin, pSum/30, pMax, aMin, aSum/30, aMax)

	// --- Indoor navigation preset: high resolution with IIR filter ---
	// ×16/×2, filter coefficient 16, normal mode at ~26 Hz. Use the second
	// and third oversampling codes (×2 / ×16) to match the datasheet's
	// indoor-navigation preset (temperature is oversampled less because the
	// pressure coefficient needs a stable reference).
	chip.Configure( // Configure chip, (osrs_t=×2, osrs_p=×16, mode=normal, filter=16, t_sb=0.5ms) → error
		pressure.BMP280OSRSX2,
		pressure.BMP280OSRSX16,
		pressure.BMP280ModeNormal,
		pressure.BMP280Filter16,
		pressure.BMP280TSB0p5MS,
	)

	alts2 := make([]float32, 0, 30)
	aMin2, aMax2 := float32(math.MaxFloat32), float32(-math.MaxFloat32)
	for n := 0; n < 30; n++ {
		t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Pressure() // Read pressure, () → (float32 hPa, error)
		if err != nil {
			panic(err)
		}
		a, err := chip.Altitude(1013.25) // Compute altitude, (sea_level_hpa=1013.25) → (float32 m, error)
		if err != nil {
			panic(err)
		}
		alts2 = append(alts2, a)
		if a < aMin2 {
			aMin2 = a
		}
		if a > aMax2 {
			aMax2 = a
		}
		_ = t
		_ = p
		fmt.Printf("%2ds: alt=%.4f m\n", n, a)
		time.Sleep(time.Second)
	}

	var sumSq float32
	for _, v := range alts2 {
		d := v - aSum/30
		sumSq += d * d
	}
	std := float32(math.Sqrt(float64(sumSq / float32(len(alts2)))))
	fmt.Printf("Navigation: alt min=%.4f max=%.4f std=%.4f m\n", aMin2, aMax2, std)
}

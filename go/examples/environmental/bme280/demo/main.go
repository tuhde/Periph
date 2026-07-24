//go:build linux && !tinygo

// BME280 demo example — Linux host.
//
// Indoor environmental logger. Polls all three sensors every second for
// 15 readings. The user is prompted to breathe gently on the sensor after
// sample 7 to demonstrate the humidity channel's response and the dew-point
// alarm use case. After the run, prints min/mean/max for each value.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x76"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x76) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := environmental.NewBME280Full(tr) // Create BME280 driver, (transport) → (*BME280Full, error)
	if err != nil {
		panic(err)
	}

	// --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
	// BME280 datasheet "weather monitoring" preset: minimum power,
	// single-shot, ~9 ms per cycle. Sleep between samples to demonstrate
	// battery-friendly indoor monitoring.
	chip.Configure( // Configure chip, (osrs_t=×1, osrs_p=×1, osrs_h=×1, mode=forced, filter=off, t_sb=0) → error
		environmental.BME280OSRSX1,
		environmental.BME280OSRSX1,
		environmental.BME280OSRSX1,
		environmental.BME280ModeForced,
		environmental.BME280FilterOff,
		environmental.BME280TSB0p5MS,
	)

	temps := make([]float32, 0, 15)
	hums := make([]float32, 0, 15)
	pressures := make([]float32, 0, 15)
	dews := make([]float32, 0, 15)

	for n := 0; n < 15; n++ {
		if n == 7 {
			// --- Half-way: breathe gently on the sensor for 3 seconds ---
			// User exposes the sensor to humid exhaled air; humidity climbs
			// from ~40 %RH toward ~80 %RH, dew point spikes toward ambient
			// temperature, pressure stays flat, temperature rises only
			// slightly. Demonstrates the humidity channel's response and
			// the dew-point alarm use case.
			fmt.Println("--- Breathe gently on the sensor for 3 seconds ---")
			time.Sleep(3 * time.Second)
		}

		t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Pressure() // Read pressure, () → (float32 hPa, error)
		if err != nil {
			panic(err)
		}
		h, err := chip.Humidity() // Read humidity, () → (float32 %RH, error)
		if err != nil {
			panic(err)
		}
		d, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		temps = append(temps, t)
		hums = append(hums, h)
		pressures = append(pressures, p)
		dews = append(dews, d)
		fmt.Printf("%2d: T=%.1f C, RH=%.1f %%, P=%.1f hPa, dew=%.1f C\n", n, t, h, p, d)
		time.Sleep(time.Second)
	}

	stats := func(v []float32) (float32, float32, float32) {
		var sum, min, max float32
		min = v[0]
		max = v[0]
		for _, x := range v {
			sum += x
			if x < min {
				min = x
			}
			if x > max {
				max = x
			}
		}
		return min, sum / float32(len(v)), max
	}
	tMin, tAvg, tMax := stats(temps)
	hMin, hAvg, hMax := stats(hums)
	pMin, pAvg, pMax := stats(pressures)
	dMin, dAvg, dMax := stats(dews)
	fmt.Printf("T:   %.1f/%.1f/%.1f C\n", tMin, tAvg, tMax)
	fmt.Printf("RH:  %.1f/%.1f/%.1f %%\n", hMin, hAvg, hMax)
	fmt.Printf("P:   %.1f/%.1f/%.1f hPa\n", pMin, pAvg, pMax)
	fmt.Printf("dew: %.1f/%.1f/%.1f C\n", dMin, dAvg, dMax)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

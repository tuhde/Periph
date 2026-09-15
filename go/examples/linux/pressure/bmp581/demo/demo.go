//go:build linux && !tinygo

// BMP581 demo — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x46"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x46) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewBMP581Full(conn, false) // Create BMP581 driver, (connection) → (*BMP581Full, error)
	if err != nil {
		panic(err)
	}

	// --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
	err = chip.Configure(0x17, pressure.BMP581OSR16X, pressure.BMP581OSR4X, true) // Configure chip, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → error
	if err != nil {
		panic(err)
	}

	var pressures, temps, alts [300]float32
	for n := 0; n < 300; n++ {
		p, err := chip.Pressure() // Read pressure, () → (float32 Pa, error)
		if err != nil {
			panic(err)
		}
		t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		a, err := chip.Altitude(101325.0) // Compute altitude, (sea_level_pa=101325.0) → (float32, error)
		if err != nil {
			panic(err)
		}
		pressures[n] = p
		temps[n] = t
		alts[n] = a
		if n%10 == 0 {
			start := n - 10
			if start < 0 {
				start = 0
			}
			span := n
			if span > 10 {
				span = 10
			}
			var mp, mt, ma float64
			for k := start; k < n; k++ {
				mp += float64(pressures[k])
				mt += float64(temps[k])
				ma += float64(alts[k])
			}
			if span > 0 {
				mp /= float64(span)
				mt /= float64(span)
				ma /= float64(span)
			}
			fmt.Printf("%d0s: rolling P=%.1f Pa  T=%.2f C  alt=%.2f m\n", n/10, mp, mt, ma)
		}
		time.Sleep(100 * time.Millisecond)
	}

	amin, amax := alts[0], alts[0]
	for _, a := range alts {
		if a < amin {
			amin = a
		}
		if a > amax {
			amax = a
		}
	}
	fmt.Printf("Bypass: alt min=%.3f max=%.3f spread=%.3f m\n", amin, amax, amax-amin)

	err = chip.SetIIRFilter(pressure.BMP581IIRCoeff3, pressure.BMP581IIRBypass) // Set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → error
	if err != nil {
		panic(err)
	}

	var alts2 [300]float32
	for n := 0; n < 300; n++ {
		_, err := chip.Pressure() // Read pressure, () → (float32 Pa, error)
		if err != nil {
			panic(err)
		}
		a, err := chip.Altitude(101325.0) // Compute altitude, (sea_level_pa=101325.0) → (float32, error)
		if err != nil {
			panic(err)
		}
		alts2[n] = a
		time.Sleep(100 * time.Millisecond)
	}
	amin2, amax2 := alts2[0], alts2[0]
	for _, a := range alts2 {
		if a < amin2 {
			amin2 = a
		}
		if a > amax2 {
			amax2 = a
		}
	}
	fmt.Printf("IIR=3:  alt min=%.3f max=%.3f spread=%.3f m\n", amin2, amax2, amax2-amin2)

	var pmin, pmax, psum float32 = pressures[0], pressures[0], 0
	for _, p := range pressures {
		if p < pmin {
			pmin = p
		}
		if p > pmax {
			pmax = p
		}
		psum += p
	}
	fmt.Printf("Min P=%.1f, max P=%.1f, mean P=%.1f Pa\n", pmin, pmax, psum/float32(len(pressures)))
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
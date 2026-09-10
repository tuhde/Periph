//go:build tinygo

// BMP581 demo — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
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

	conn := connection.NewI2CConnection(i2c, 0x46, nil, nil)        // Create I2C connection, (i2c, addr=0x46) → (*I2CConnection)
	chip, err := pressure.NewBMP581Full(conn, false)                  // Create BMP581 driver, (connection) → (*BMP581Full, error)
	if err != nil {
		panic(err)
	}

	// --- Precision altimeter: 10 Hz NORMAL mode ---
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

	for {
		time.Sleep(time.Hour)
	}
}
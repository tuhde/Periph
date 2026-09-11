//go:build tinygo

// LPS33HW demo example — TinyGo / Raspberry Pi Pico W.
//
// Altimeter scenario: read pressure and temperature at 10 Hz and compute
// altitude above sea level using the barometric formula. Every 10 seconds,
// AUTOZERO re-zeros the sensor to the current ambient pressure.
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

	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil) // Create I2C connection, (i2c, addr=0x5C) → (*I2CConnection)
	chip, err := pressure.NewLPS33HWFull(conn, 0x5C)         // Create LPS33HW driver, (connection, addr=0x5C) → (*LPS33HWFull, error)
	if err != nil {
		panic(err)
	}

	// --- Initialization and configuration for altimeter use ---
	// 10 Hz ODR + BDU=1 + EN_LPFP=1/LPFP_CFG=1 (ODR/20) gives smooth pressure
	// readings. The barometric formula needs pressure ratios, so reducing
	// short-term noise is the dominant accuracy lever.
	chip.Configure( // Configure chip, (odr=10 Hz, bdu=true, enLpfp=true, lpfpCfg=ODR/20, lcEn=false, sim=false) → error
		pressure.LPS33HWODR10Hz, 1, 1, pressure.LPS33HWLPFPBWODR20, 0, 0,
	)
	chip.ResetLpf() // Reset LPF, () → error
	                 // flushes transitory state after enabling EN_LPFP

	const seaLevelPa float32 = 101325.0

	// --- Main loop ---
	// Pressure is polled on P_DA rather than by fixed delay so we read the
	// freshest possible sample every cycle.
	for n := 0; n < 30; n++ {
		p, err := chip.Pressure() // Read pressure, () → (float32 Pa, error)
		if err != nil {
			println("read press:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			println("read temp:", err.Error())
			time.Sleep(time.Second)
			continue
		}

		// --- Altitude calculation ---
		// Barometric formula: altitude_m = 44330 × (1 − (P / P0)^(1/5.255)).
		ratio := float64(p / seaLevelPa)
		altitudeM := 44330.0 * (1.0 - math.Pow(ratio, 1.0/5.255))
		fmt.Printf("%2ds: %.2f C, %.1f Pa, alt=%.1f m\n", n, t, p, altitudeM)

		// --- Autozero every 10 seconds ---
		// Re-zeroing removes slow atmospheric pressure drift for relative
		// altitude measurements.
		if n > 0 && n%10 == 0 {
			chip.SetAutozero() // Set AUTOZERO, () → error
			fmt.Println("Reference updated.")
		}

		time.Sleep(time.Second)
	}
}
//go:build tinygo

// ENS160 demo example — TinyGo / Raspberry Pi Pico W.
//
// Indoor air-quality monitor: polls the validity flag until it
// reaches OK, then enters a loop printing AQI, TVOC, and eCO2 with a
// human-readable AQI label every second.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/transport"
)

// aqiLabel maps the 1-5 UBA scale to a human-readable string.
func aqiLabel(aqi int) string {
	switch aqi {
	case 1:
		return "Excellent"
	case 2:
		return "Good"
	case 3:
		return "Moderate"
	case 4:
		return "Poor"
	case 5:
		return "Unhealthy"
	default:
		return "Unknown"
	}
}

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x53) // Create I2C transport, (i2c, addr=0x53) → (*I2CTransport)

	chip, err := gas.NewENS160Full(tr) // Create ENS160 driver, (transport) → (*ENS160Full, error)
	if err != nil {
		panic(err)
	}

	// --- If you have an external T/RH sensor, set it here ---
	// The ENS160 has its own internal default of 25 C / 50 %RH; feeding
	// it real ambient values compensates for humidity sensitivity in
	// the MOX sensing elements.
	// chip.SetCompensation(22.5, 45.0) // Set T/RH compensation, (temp_celsius, rh_percent) → error

	fmt.Printf("%-8s %-8s %-12s %-12s %-12s\n", "Sample", "AQI", "Label", "TVOC (ppb)", "eCO2 (ppm)")
	for n := 0; n < 60; n++ {
		// --- Poll the validity flag until data is trustworthy ---
		// The ENS160 powers up in "Initial Start-up" (VALIDITY=2) for up
		// to 1 hour, then enters a 3-minute warm-up (VALIDITY=1) after
		// each idle period. The driver refuses to return air-quality
		// values until VALIDITY=0 so we wait for that here.
		status, err := chip.Status() // Read validity flag, () → (int, error)
		if err != nil {
			panic(err)
		}
		if status != gas.ValidityOK {
			fmt.Printf("Warming up (VALIDITY=%d), retrying...\n", status)
			time.Sleep(2 * time.Second)
			continue
		}

		aqi, tvoc, eco2, err := chip.ReadAirQuality() // Read air quality, () → (int, float32 ppb, float32 ppm, error)
		if err != nil {
			fmt.Printf("read: %v\n", err)
			time.Sleep(time.Second)
			continue
		}

		// --- AQI 1-5 maps to a UBA category ---
		// The UBA scale rates indoor air from "Excellent" (no
		// objections) to "Unhealthy" (ventilation required).  See
		// the spec for the TVOC/ppm breakpoints.
		fmt.Printf("%-8d %-8d %-12s %-12.0f %-12.0f\n", n, aqi, aqiLabel(aqi), tvoc, eco2)
		time.Sleep(time.Second)
	}
}

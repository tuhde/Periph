//go:build tinygo

// AHT21 demo example — TinyGo / Raspberry Pi Pico W.
//
// Weather-station style logger. Initializes the sensor, prints the
// calibration status, then logs temperature, humidity, and computed
// dew point every 5 seconds. (Cap the loop count to keep the UF2
// from running forever; on a Pico W this just needs to demonstrate
// the demo flow.)
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C0
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x38)      // Create I2C transport, (i2c, addr=0x38) → (*I2CTransport)
	chip, err := environmental.NewAHT21Full(tr)   // Create AHT21 driver, (transport) → (*AHT21Full, error)
	if err != nil {
		panic(err)
	}

	// --- Verify calibration before starting the logging session ---
	// Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
	// the driver already sent the calibration init sequence during New.
	cal, err := chip.IsCalibrated()
	if err != nil {
		panic(err)
	}
	fmt.Printf("Calibrated: %v\n", cal) // Check calibration status, () → (bool, error)

	fmt.Printf("%-8s %-10s %-10s %-10s\n", "Time", "T (C)", "RH (%)", "Dew (C)")
	for n := 0; n < 60; n++ {
		// --- Each reading requires an 80 ms measurement cycle ---
		// The sensor cannot output data faster than this; the driver
		// handles the trigger + wait internally.
		t, h, crcOk, err := chip.ReadWithCrc()
		if err != nil {
			fmt.Printf("read err: %v\n", err)
			time.Sleep(5 * time.Second)
			continue
		}
		if !crcOk {
			fmt.Printf("CRC error at sample %d\n", n)
			time.Sleep(5 * time.Second)
			continue
		}

		// --- Magnus formula dew-point approximation ---
		// gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
		// dew_point = (243.04 * gamma) / (17.625 - gamma)
		// Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
		tF, hF := float64(t), float64(h)
		gamma := math.Log(hF/100.0) + (17.625*tF)/(243.04+tF)
		dew := (243.04 * gamma) / (17.625 - gamma)

		fmt.Printf("%-8d %-10.2f %-10.2f %-10.2f\n", n, t, h, dew) // Read with CRC verification, () → (float32 °C, float32 %RH, bool, error)
		time.Sleep(5 * time.Second)
	}
}

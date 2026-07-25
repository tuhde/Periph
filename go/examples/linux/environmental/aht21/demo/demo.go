//go:build linux && !tinygo

// AHT21 demo example — Linux host.
//
// Weather-station style logger. Initializes the sensor, prints the
// calibration status, then logs temperature, humidity, and computed
// dew point every 5 seconds.
package main

import (
	"fmt"
	"math"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x38) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := environmental.NewAHT21Full(tr) // Create AHT21 driver, (transport) → (*AHT21Full, error)
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
			panic(err)
		}
		if !crcOk {
			fmt.Fprintf(os.Stderr, "CRC error at sample %d\n", n)
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

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// LPS33HW demo example — Linux host.
//
// Altimeter scenario: read pressure and temperature at 10 Hz and compute
// altitude above sea level using the barometric formula. Every 10 seconds,
// AUTOZERO re-zeros the sensor to the current ambient pressure.
package main

import (
	"fmt"
	"math"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5C) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewLPS33HWFull(conn, uint8(addr)) // Create LPS33HW driver, (connection, addr=0x5C) → (*LPS33HWFull, error)
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
	// freshest possible sample every cycle. With ODR=10 Hz the loop runs at
	// ~10 Hz; printing once per second gives a clean per-second summary.
	for n := 0; n < 30; n++ {
		p, err := chip.Pressure() // Read pressure, () → (float32 Pa, error)
		if err != nil {
			panic(err)
		}
		t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}

		// --- Altitude calculation ---
		// Barometric formula: altitude_m = 44330 × (1 − (P / P0)^(1/5.255)).
		// Valid for troposphere below ~11 km; for outdoor altimetry the
		// absolute altitude depends on the local sea-level reference, but
		// relative changes (e.g. drone altitude tracking) are accurate.
		ratio := float64(p / seaLevelPa)
		altitudeM := 44330.0 * (1.0 - math.Pow(ratio, 1.0/5.255))
		fmt.Printf("%2ds: %.2f C, %.1f Pa, alt=%.1f m\n", n, t, p, altitudeM)

		// --- Autozero every 10 seconds ---
		// Re-zeroing removes slow atmospheric pressure drift for relative
		// altitude measurements. The current reading is captured into REF_P
		// and subtracted from all subsequent samples.
		if n > 0 && n%10 == 0 {
			chip.SetAutozero() // Set AUTOZERO, () → error
			fmt.Println("Reference updated.")
		}

		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
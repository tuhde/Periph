//go:build linux && !tinygo

// HY3131 demo example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewSPIConnection(bus, device, 0, 1_000_000, nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	// --- Bench voltmeter with on-chip temperature and AC analysis ---
	// A 0–1.2 V source sits between PB2 and AGND. AD1 runs at ~40 Hz with the chopper on
	// for offset-free DC readings; AD2 + LPF + RMS + peak hold watch the same node for AC.
	f, err := adcdac.NewHY3131Full(conn, 1.2) // Create HY3131 driver, (connection, vref=1.2 V) → (*HY3131Full, error)
	if err != nil {
		panic(err)
	}
	err = f.ConfigureAD1(4, 2, 0, 0, 0, 0) // Configure AD1, (osr=7, chop=0, inputGain=0, refGain=0, offset=0, buffers=0) → error
	if err != nil {
		panic(err)
	}

	// --- One-point temperature calibration ---
	// Run the board at 25 °C (or replace with a stored constant) and remember the code.
	tcode25, err := f.ReadTemperatureCode() // Read temperature code, () → (int32, error)
	if err != nil {
		panic(err)
	}

	// --- Enable AC measurement chain on the same input ---
	err = f.EnableAD2(5, 1, 0, false, false, 2, false) // Enable AD2, (osr=5, inputGain=1, refGain=0, chop=false, clkDiv4=false, source=0, useAD3=false) → error
	if err != nil {
		panic(err)
	}
	err = f.EnableLPF(3) // Enable low-pass filter, (bw=0) → error
	if err != nil {
		panic(err)
	}
	err = f.EnableRMS() // Enable RMS converter, () → error
	if err != nil {
		panic(err)
	}
	err = f.EnablePeakHold(0) // Enable peak hold, (source=0) → error
	if err != nil {
		panic(err)
	}

	for {
		v, err := f.ReadVoltage() // Read AD1 voltage, () → (float64 V, error)
		if err != nil {
			panic(err)
		}
		t, err := f.ReadTemperature(tcode25) // Read die temperature, (tcode25) → (float64 °C, error)
		if err != nil {
			panic(err)
		}
		rms, err := f.ReadRMS() // Read RMS, () → (float64 LSB, error)
		if err != nil {
			panic(err)
		}
		lo, hi, err := f.ReadPeak() // Read peak hold, () → (int32, int32 19-bit, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("V=%.4f V  T=%.1f C  rms=%.0f  p-p=%d\n", v, t, rms, hi-lo)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

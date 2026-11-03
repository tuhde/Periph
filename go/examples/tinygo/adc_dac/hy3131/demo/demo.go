//go:build tinygo

// HY3131 demo example — TinyGo target.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.SPI0.Configure(machine.SPIConfig{
		Frequency: 1_000_000,
		Mode:      0,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
	})
	cs := machine.GP17
	conn := connection.NewSPIConnection(machine.SPI0, cs, nil, nil)

	// --- Bench voltmeter with on-chip temperature and AC analysis ---
	// A 0–1.2 V source sits between PB2 and AGND. AD1 runs at ~40 Hz with the chopper on
	// for offset-free DC readings; AD2 + LPF + RMS + peak hold watch the same node for AC.
	f, err := adcdac.NewHY3131Full(conn, 1.2) // Create HY3131 driver, (connection, vref=1.2 V) → (*HY3131Full, error)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	err = f.ConfigureAD1(4, 2, 0, 0, 0, 0) // Configure AD1, (osr=7, chop=0, inputGain=0, refGain=0, offset=0, buffers=0) → error
	if err != nil {
		fmt.Println("op:", err)
		return
	}

	// --- One-point temperature calibration ---
	// Run the board at 25 °C (or replace with a stored constant) and remember the code.
	tcode25, err := f.ReadTemperatureCode() // Read temperature code, () → (int32, error)
	if err != nil {
		fmt.Println("read:", err)
		return
	}

	// --- Enable AC measurement chain on the same input ---
	err = f.EnableAD2(5, 1, 0, false, false, 2, false) // Enable AD2, (osr=5, inputGain=1, refGain=0, chop=false, clkDiv4=false, source=0, useAD3=false) → error
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	err = f.EnableLPF(3) // Enable low-pass filter, (bw=0) → error
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	err = f.EnableRMS() // Enable RMS converter, () → error
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	err = f.EnablePeakHold(0) // Enable peak hold, (source=0) → error
	if err != nil {
		fmt.Println("op:", err)
		return
	}

	for {
		v, err := f.ReadVoltage() // Read AD1 voltage, () → (float64 V, error)
		if err != nil {
			fmt.Println("read:", err)
			return
		}
		t, err := f.ReadTemperature(tcode25) // Read die temperature, (tcode25) → (float64 °C, error)
		if err != nil {
			fmt.Println("read:", err)
			return
		}
		rms, err := f.ReadRMS() // Read RMS, () → (float64 LSB, error)
		if err != nil {
			fmt.Println("read:", err)
			return
		}
		lo, hi, err := f.ReadPeak() // Read peak hold, () → (int32, int32 19-bit, error)
		if err != nil {
			fmt.Println("read:", err)
			return
		}
		fmt.Printf("V=%.4f V  T=%.1f C  rms=%.0f  p-p=%d\n", v, t, rms, hi-lo)
		time.Sleep(time.Second)
	}
}

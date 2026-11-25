//go:build tinygo

// HY3131 complete example — TinyGo target.
package main

import (
	"fmt"
	"machine"

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

	f, err := adcdac.NewHY3131Full(conn, 1.2) // Create HY3131 driver, (connection, vref=1.2 V) → (*HY3131Full, error)
	// resets the f and applies the Minimal defaults (AD1 on PB2/AGND, 5 Hz)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	err = f.ConfigureAD1(4, 2, 0, 0, 0, 0) // Configure AD1, (osr=7, chop=0, inputGain=0, refGain=0, offset=0, buffers=0) → error
	// OSR 4096 (~40 Hz), chopper on (offset cancelled, rate halved), gain 0.9
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	err = f.SetAD1Input(10, 3, 2) // Select AD1 inputs, (pos=SAD1FP 0–15, neg=SAD1FN 0–7, filterR=2) → error
	// PB2 vs AGND with the filter resistor bypassed
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	err = f.SetAD1Reference(1, 1) // Select AD1 reference, (high=0–6, low=0–6) → error
	// REFO (+) / AGND (−)
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	raw, err := f.ReadRaw() // Read AD1 code, () → (int32 24-bit, error)
	// waits for AD1F in INTF, returns the sign-extended code
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	v, err := f.ReadVoltage() // Read AD1 voltage, () → (float64 V, error)
	// code / 2^23 · RG − DCOS, scaled by vref / input gain
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	fmt.Println("raw=", raw, "v=", v)

	tcode, err := f.ReadTemperatureCode() // Read temperature code, () → (int32, error)
	// (AD1[TS pair 2] − AD1[TS pair 1]) / 2; note this value at 25 °C
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	t, err := f.ReadTemperature(tcode) // Read die temperature, (tcode25) → (float64 °C, error)
	// one-point calibrated against tcode25
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	fmt.Println("temperature=", t)

	err = f.EnableAD2(5, 1, 0, false, false, 0, false) // Enable AD2, (osr=5, inputGain=1, refGain=0, chop=false, clkDiv4=false, source=0, useAD3=false) → error
	// AD2 samples OP1O at gain 1.0, OSR 1024
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	ad2, err := f.ReadAD2Raw() // Read AD2 code, () → (int32 19-bit, error)
	// waits for AD2F
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	err = f.EnableLPF(0) // Enable low-pass filter, (bw=0) → error
	// OSR4 = 128
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	lpf, err := f.ReadLPFRaw() // Read LPF code, () → (int32 19-bit, error)
	// waits for LPFF
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	err = f.EnableRMS() // Enable RMS converter, () → error
	// accumulates the squared LPF input
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	rms, err := f.ReadRMS() // Read RMS, () → (float64 LSB, error)
	// square root of the accumulator
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	err = f.EnablePeakHold(0) // Enable peak hold, (source=0) → error
	// tracks AD2 min/max; re-enabling resets
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	lo, hi, err := f.ReadPeak() // Read peak hold, () → (int32, int32 19-bit, error)
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	fmt.Println("ad2=", ad2, "lpf=", lpf, "rms=", rms, "peak=", lo, hi)

	err = f.StartCounter(0) // Start frequency counter, (gatePreload=0) → error
	// enables the CNT buffer and the counter
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	cta, ctb, ctc, overflow, err := f.ReadCounter() // Read counter, () → (cta, ctb, ctc uint32, overflow bool, error)
	// waits for CTF; overflow=true means the measurement is invalid
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	fmt.Println("counter=", cta, ctb, ctc, overflow)

	err = f.EnableInterrupts(0x04) // Enable interrupts, (mask) → error
	// AD1IE: DO goes high while CS is high and AD1F is set
	if err != nil {
		fmt.Println("op:", err)
		return
	}
	flags, err := f.ReadFlags() // Read and clear INTF, () → (uint8, error)
	// bit 2 = AD1F
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	fmt.Println("flags=", flags)

	err = f.Reset() // Software reset, () → error
	// writes 0x60 to R37 and re-applies the Minimal defaults
	if err != nil {
		fmt.Println("op:", err)
		return
	}
}

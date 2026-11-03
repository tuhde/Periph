//go:build linux && !tinygo

// HY3131 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

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

	f, err := adcdac.NewHY3131Full(conn, 1.2) // Create HY3131 driver, (connection, vref=1.2 V) → (*HY3131Full, error)
	// resets the f and applies the Minimal defaults (AD1 on PB2/AGND, 5 Hz)
	if err != nil {
		panic(err)
	}
	err = f.ConfigureAD1(4, 2, 0, 0, 0, 0) // Configure AD1, (osr=7, chop=0, inputGain=0, refGain=0, offset=0, buffers=0) → error
	// OSR 4096 (~40 Hz), chopper on (offset cancelled, rate halved), gain 0.9
	if err != nil {
		panic(err)
	}
	err = f.SetAD1Input(10, 3, 2) // Select AD1 inputs, (pos=SAD1FP 0–15, neg=SAD1FN 0–7, filterR=2) → error
	// PB2 vs AGND with the filter resistor bypassed
	if err != nil {
		panic(err)
	}
	err = f.SetAD1Reference(1, 1) // Select AD1 reference, (high=0–6, low=0–6) → error
	// REFO (+) / AGND (−)
	if err != nil {
		panic(err)
	}
	raw, err := f.ReadRaw() // Read AD1 code, () → (int32 24-bit, error)
	// waits for AD1F in INTF, returns the sign-extended code
	if err != nil {
		panic(err)
	}
	v, err := f.ReadVoltage() // Read AD1 voltage, () → (float64 V, error)
	// code / 2^23 · RG − DCOS, scaled by vref / input gain
	if err != nil {
		panic(err)
	}
	fmt.Println("raw=", raw, "v=", v)

	tcode, err := f.ReadTemperatureCode() // Read temperature code, () → (int32, error)
	// (AD1[TS pair 2] − AD1[TS pair 1]) / 2; note this value at 25 °C
	if err != nil {
		panic(err)
	}
	t, err := f.ReadTemperature(tcode) // Read die temperature, (tcode25) → (float64 °C, error)
	// one-point calibrated against tcode25
	if err != nil {
		panic(err)
	}
	fmt.Println("temperature=", t)

	err = f.EnableAD2(5, 1, 0, false, false, 0, false) // Enable AD2, (osr=5, inputGain=1, refGain=0, chop=false, clkDiv4=false, source=0, useAD3=false) → error
	// AD2 samples OP1O at gain 1.0, OSR 1024
	if err != nil {
		panic(err)
	}
	ad2, err := f.ReadAD2Raw() // Read AD2 code, () → (int32 19-bit, error)
	// waits for AD2F
	if err != nil {
		panic(err)
	}
	err = f.EnableLPF(0) // Enable low-pass filter, (bw=0) → error
	// OSR4 = 128
	if err != nil {
		panic(err)
	}
	lpf, err := f.ReadLPFRaw() // Read LPF code, () → (int32 19-bit, error)
	// waits for LPFF
	if err != nil {
		panic(err)
	}
	err = f.EnableRMS() // Enable RMS converter, () → error
	// accumulates the squared LPF input
	if err != nil {
		panic(err)
	}
	rms, err := f.ReadRMS() // Read RMS, () → (float64 LSB, error)
	// square root of the accumulator
	if err != nil {
		panic(err)
	}
	err = f.EnablePeakHold(0) // Enable peak hold, (source=0) → error
	// tracks AD2 min/max; re-enabling resets
	if err != nil {
		panic(err)
	}
	lo, hi, err := f.ReadPeak() // Read peak hold, () → (int32, int32 19-bit, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("ad2=", ad2, "lpf=", lpf, "rms=", rms, "peak=", lo, hi)

	err = f.StartCounter(0) // Start frequency counter, (gatePreload=0) → error
	// enables the CNT buffer and the counter
	if err != nil {
		panic(err)
	}
	cta, ctb, ctc, overflow, err := f.ReadCounter() // Read counter, () → (cta, ctb, ctc uint32, overflow bool, error)
	// waits for CTF; overflow=true means the measurement is invalid
	if err != nil {
		panic(err)
	}
	fmt.Println("counter=", cta, ctb, ctc, overflow)

	err = f.EnableInterrupts(0x04) // Enable interrupts, (mask) → error
	// AD1IE: DO goes high while CS is high and AD1F is set
	if err != nil {
		panic(err)
	}
	flags, err := f.ReadFlags() // Read and clear INTF, () → (uint8, error)
	// bit 2 = AD1F
	if err != nil {
		panic(err)
	}
	fmt.Println("flags=", flags)

	err = f.Reset() // Software reset, () → error
	// writes 0x60 to R37 and re-applies the Minimal defaults
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

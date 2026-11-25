//go:build linux && !tinygo

// ADS1115 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, adcdac.ADS1115I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	d, err := adcdac.NewADS1115Full(conn) // Create ADS1115 Full driver, (conn) → (*ADS1115Full, error)
	// no bus traffic; Config word is rebuilt per conversion
	if err != nil {
		panic(err)
	}
	raw, err := d.ReadRaw(2) // Read raw code, (channel=0) → (int16, error)
	// single-shot conversion, signed 16-bit
	if err != nil {
		panic(err)
	}
	v, err := d.ReadVoltage(2) // Read voltage, (channel=0) → (float32 V, error)
	// raw × LSB()
	if err != nil {
		panic(err)
	}
	fmt.Printf("raw %v\n", raw)
	fmt.Printf("voltage %v\n", v)
	err = d.SetGain(adcdac.ADS1115PGA4096) // Set gain, (pga 0-5) → error
	// ±4.096 V full scale
	if err != nil {
		panic(err)
	}
	fmt.Printf("gain %v\n", d.GetGain())
	fs := d.FullScale() // Full scale, () → float32 V
	// ±FSR of the current gain
	lsb := d.LSB() // LSB, () → float32 V
	// FullScale / 32768
	fmt.Printf("full scale %v\n", fs)
	fmt.Printf("lsb %v\n", lsb)
	err = d.SetDataRate(adcdac.ADS1115DR250) // Set data rate, (dr 0-7) → error
	// DR field of the Config word
	if err != nil {
		panic(err)
	}
	sps := d.DataRateSps() // Data rate, () → uint16 SPS
	// samples per second of the current setting
	fmt.Printf("rate SPS %v\n", sps)
	diff, err := d.ReadDifferential(adcdac.ADS1115Diff23) // Read differential, (pair 0-3) → (float32 V, error)
	// single-shot MUX = pair
	if err != nil {
		panic(err)
	}
	draw, err := d.ReadDifferentialRaw(adcdac.ADS1115Diff01) // Read differential raw, (pair 0-3) → (int16, error)
	// signed 16-bit code
	if err != nil {
		panic(err)
	}
	fmt.Printf("differential %v\n", diff)
	fmt.Printf("differential raw %v\n", draw)
	err = d.StartContinuous(adcdac.ADS1115MuxAIN0GND) // Start continuous, (mux=MuxAIN0GND 0-7) → error
	// MODE ← 0
	if err != nil {
		panic(err)
	}
	time.Sleep(50 * time.Millisecond)
	craw, err := d.ReadContinuousRaw() // Read continuous raw, () → (int16, error)
	// CONVERSION without touching Config
	if err != nil {
		panic(err)
	}
	cv, err := d.ReadContinuous() // Read continuous, () → (float32 V, error)
	// ReadContinuousRaw() × LSB()
	if err != nil {
		panic(err)
	}
	fmt.Printf("continuous raw %v\n", craw)
	fmt.Printf("continuous %v\n", cv)
	err = d.StopContinuous() // Stop continuous, () → error
	// MODE ← 1
	if err != nil {
		panic(err)
	}
	busy, err := d.IsConverting() // Check conversion, () → (bool, error)
	// CONFIG.OS == 0
	if err != nil {
		panic(err)
	}
	cfg, err := d.ReadConfig() // Read CONFIG, () → (uint16, error)
	// raw 16-bit register
	if err != nil {
		panic(err)
	}
	fmt.Printf("converting %v\n", busy)
	fmt.Printf("config %v\n", cfg)
	err = d.SetThresholds(0.5, 3.0) // Set thresholds, (low V, high V) → error
	// converted with the current LSB(), clamped
	if err != nil {
		panic(err)
	}
	err = d.SetLowThresholdRaw(4000) // Set low threshold, (raw) → error
	// LO_THRESH ← raw & 0xFFFF
	if err != nil {
		panic(err)
	}
	err = d.SetHighThresholdRaw(20000) // Set high threshold, (raw) → error
	// HI_THRESH ← raw & 0xFFFF
	if err != nil {
		panic(err)
	}
	lo, hi, err := d.GetThresholdsRaw() // Get thresholds, () → (int16, int16, error)
	// read back, sign-extended
	if err != nil {
		panic(err)
	}
	fmt.Printf("low threshold %v\n", lo)
	fmt.Printf("high threshold %v\n", hi)
	err = d.ConfigureComparator(adcdac.ADS1115CompWindow, adcdac.ADS1115ActiveLow, true, adcdac.ADS1115Queue2) // Configure comparator, (mode, polarity, latch, queue) → error
	// window mode, latching, 2-sample queue
	if err != nil {
		panic(err)
	}
	err = d.ClearAlert() // Clear alert, () → error
	// reads CONVERSION to release the latch
	if err != nil {
		panic(err)
	}
	err = d.DisableComparator() // Disable comparator, () → error
	// COMP_QUE ← 11
	if err != nil {
		panic(err)
	}
	err = d.EnableInterrupt(adcdac.ADS1115SourceConversionReady) // Enable interrupt, (source) → error
	// ALERT/RDY signals conversion-ready
	if err != nil {
		panic(err)
	}
	status, err := d.PollInterrupt() // Poll interrupt, () → (uint8 source, error)
	// enabled source if ALERT/RDY is asserted
	if err != nil {
		panic(err)
	}
	fmt.Printf("poll %v\n", status)
	err = d.OnInterrupt(func(s uint8) { fmt.Println("alert", s) }) // Subscribe, (callback) → error
	// pin edge, or a polling goroutine without an InputPin
	if err != nil {
		panic(err)
	}
	time.Sleep(1000 * time.Millisecond)
	err = d.OffInterrupt() // Unsubscribe, () → error
	// stops delivery
	if err != nil {
		panic(err)
	}
	err = d.DisableInterrupt(adcdac.ADS1115SourceConversionReady) // Disable interrupt, (source) → error
	// releases ALERT/RDY
	if err != nil {
		panic(err)
	}
	err = d.Reset() // Reset, () → error
	// writes the reset values back
	if err != nil {
		panic(err)
	}
}

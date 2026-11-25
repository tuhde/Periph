//go:build linux && !tinygo

// ADS1014 complete example — Linux host.
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

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, adcdac.ADS1014I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	adc, err := adcdac.NewADS1014Full(conn) // Create ADS1014 Full driver, (conn) → (*ADS1014Full, error)
	// no bus traffic; defaults ±2.048 V, 1600 SPS
	must(err)

	raw, err := adc.ReadRaw() // Read raw code, () → (int16, error) (−2048…2047)
	// single-shot AIN0 − AIN1, polls OS until idle
	must(err)
	v, err := adc.ReadVoltage() // Read voltage, () → (float32 V, error)
	// raw × full-scale / 2048
	must(err)
	fmt.Printf("raw %v\n", raw)
	fmt.Printf("voltage %.4f V\n", v)

	must(adc.SetGain(adcdac.ADS1014PGA1_024)) // Set PGA, (pga 0–5) → error
	// ±1.024 V range, 0.5 mV per code
	fmt.Printf("gain %v\n", adc.GetGain()) // Read PGA code, () → uint8
	// cached PGA[2:0]
	fmt.Printf("full scale %.4f V\n", adc.FullScale()) // Read full-scale range, () → float32 V
	// ±FSR of the current gain
	fmt.Printf("lsb %.4f mV\n", adc.LSB()*1000) // Read code size, () → float32 V
	// FullScale() / 2048

	must(adc.SetDataRate(adcdac.ADS1014DR250)) // Set data rate, (dr 0–6) → error
	// DR250 = 250 SPS
	fmt.Printf("dr code %v\n", adc.GetDataRate()) // Read data-rate code, () → uint8
	// cached DR[2:0]
	fmt.Printf("sps %v\n", adc.DataRateSPS()) // Read data rate, () → uint16 SPS
	// table lookup of the DR code

	must(adc.StartContinuous()) // Start continuous conversion, () → error
	// MODE = 0 at the current data rate
	time.Sleep(20 * time.Millisecond)
	craw, err := adc.ReadContinuousRaw() // Read latest code, () → (int16, error)
	// reads CONVERSION without touching Config
	must(err)
	fmt.Printf("continuous raw %v\n", craw)
	cv, err := adc.ReadContinuous() // Read latest voltage, () → (float32 V, error)
	// raw × LSB()
	must(err)
	fmt.Printf("continuous %.4f V\n", cv)
	must(adc.StopContinuous()) // Stop continuous conversion, () → error
	// MODE = 1, back to single-shot
	busy, err := adc.IsConverting() // Check conversion in progress, () → (bool, error)
	// CONFIG.OS = 0 (single-shot only)
	must(err)
	fmt.Printf("converting %v\n", busy)
	cfg, err := adc.ReadConfig() // Read CONFIG register, () → (uint16, error)
	// raw 16-bit register
	must(err)
	fmt.Printf("config %v\n", cfg)

	must(adc.SetThresholds(-0.2, 0.5)) // Set both thresholds, (low V, high V) → error
	// converted with LSB(), clamped to the code range
	must(adc.SetLowThresholdRaw(-200)) // Set low threshold, (raw −2048…2047) → error
	// LO_THRESH = raw << 4
	must(adc.SetHighThresholdRaw(500)) // Set high threshold, (raw −2048…2047) → error
	// HI_THRESH = raw << 4
	lo, hi, err := adc.GetThresholdsRaw() // Read thresholds, () → (int16, int16, error)
	// sign-extended 12-bit codes
	must(err)
	fmt.Printf("low threshold %v\n", lo)
	fmt.Printf("high threshold %v\n", hi)

	must(adc.ConfigureComparator(adcdac.ADS1014CompWindow, adcdac.ADS1014ActiveLow, true, adcdac.ADS1014Queue2)) // Configure comparator, (mode, polarity, latch, queue) → error
	// window mode, latching, 2 consecutive conversions
	must(adc.ClearAlert()) // Release latched alert, () → error
	// reads CONVERSION
	must(adc.DisableComparator()) // Disable comparator, () → error
	// COMP_QUE = 11, ALERT/RDY high-impedance

	must(adc.EnableInterrupt(adcdac.ADS1014SourceConversionReady)) // Route conversion-ready to ALERT/RDY, (source) → error
	// HI_THRESH = 0x8000, LO_THRESH = 0, COMP_QUE = 00
	status, err := adc.PollInterrupt() // Poll ALERT/RDY, () → (uint8 source, error)
	// INT pin level, else CONFIG.OS fallback
	must(err)
	fmt.Printf("status %v\n", status)
	must(adc.DisableInterrupt(adcdac.ADS1014SourceConversionReady)) // Release ALERT/RDY, (source) → error
	// COMP_QUE = 11

	must(adc.EnableInterrupt(adcdac.ADS1014SourceComparator)) // Route comparator to ALERT/RDY, (source) → error
	// restores the thresholds set above
	must(adc.OnInterrupt(func(status uint8) { // Subscribe to ALERT/RDY, (callback) → error
		// pin edge via the connection IntPin, else a polling goroutine
		fmt.Printf("alert source %v\n", status)
	}))
	time.Sleep(1000 * time.Millisecond)
	must(adc.OffInterrupt()) // Unsubscribe, () → error
	// stops delivery
	must(adc.Reset()) // Reset to power-up defaults, () → error
	// rewrites CONFIG and both thresholds
}

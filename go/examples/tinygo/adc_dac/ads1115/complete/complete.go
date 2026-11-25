//go:build tinygo

// ADS1115 complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1115I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
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
	println("raw", raw)
	println("voltage (mV)", int32(v*1000))
	err = d.SetGain(adcdac.ADS1115PGA4096) // Set gain, (pga 0-5) → error
	// ±4.096 V full scale
	if err != nil {
		panic(err)
	}
	println("gain", d.GetGain())
	fs := d.FullScale() // Full scale, () → float32 V
	// ±FSR of the current gain
	lsb := d.LSB() // LSB, () → float32 V
	// FullScale / 32768
	println("full scale (mV)", int32(fs*1000))
	println("lsb (mV)", int32(lsb*1000))
	err = d.SetDataRate(adcdac.ADS1115DR250) // Set data rate, (dr 0-7) → error
	// DR field of the Config word
	if err != nil {
		panic(err)
	}
	sps := d.DataRateSps() // Data rate, () → uint16 SPS
	// samples per second of the current setting
	println("rate SPS", sps)
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
	println("differential (mV)", int32(diff*1000))
	println("differential raw", draw)
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
	println("continuous raw", craw)
	println("continuous (mV)", int32(cv*1000))
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
	println("converting", busy)
	println("config", cfg)
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
	println("low threshold", lo)
	println("high threshold", hi)
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
	println("poll", status)
	err = d.OnInterrupt(func(s uint8) { println("alert", s) }) // Subscribe, (callback) → error
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

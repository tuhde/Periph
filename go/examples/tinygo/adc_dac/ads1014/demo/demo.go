//go:build tinygo

// ADS1014 demo example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

// Shunt-current monitor: a 0.1 ohm shunt in the load supply line is read on
// the ±0.256 V range; the traditional comparator watches for more than 1 A.
const (
	shuntOhms = 0.1
	samples   = 20
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1014I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	adc, err := adcdac.NewADS1014Full(conn) // Create ADS1014 Full driver, (conn) → (*ADS1014Full, error)
	must(err)

	// --- Pick the range and speed for a small shunt voltage ---
	// ±0.256 V gives 0.125 mV per code; 250 SPS keeps noise low and is plenty
	// for a once-per-second report.
	must(adc.SetGain(adcdac.ADS1014PGA0_256))  // Set PGA, (pga 0–5) → error
	must(adc.SetDataRate(adcdac.ADS1014DR250)) // Set data rate, (dr 0–6) → error

	// --- Arm the over-current comparator ---
	// 0.1 V across 0.1 ohm is 1 A. Traditional comparator with hysteresis
	// (clears again below 0.08 V), latched so a brief spike is not missed, and
	// 4 consecutive conversions are needed to assert.
	must(adc.SetThresholds(0.08, 0.1))                                                                                // Set both thresholds, (low V, high V) → error
	must(adc.ConfigureComparator(adcdac.ADS1014CompTraditional, adcdac.ADS1014ActiveLow, true, adcdac.ADS1014Queue4)) // Configure comparator, (mode, polarity, latch, queue) → error
	must(adc.EnableInterrupt(adcdac.ADS1014SourceComparator))                                                         // Route comparator to ALERT/RDY, (source) → error

	// --- Monitor the current ---
	// The pin level is not wired in this demo, so the alert state is inferred
	// from the measured current; ClearAlert() releases the latch afterwards.
	for i := 0; i < samples; i++ {
		v, err := adc.ReadVoltage() // Read voltage, () → (float32 V, error)
		must(err)
		amps := v / shuntOhms
		println("current (mA)", int32((amps)*1000))
		if amps > 1.0 {
			println("over-current", true)
			must(adc.ClearAlert()) // Release latched alert, () → error
		}
		time.Sleep(1000 * time.Millisecond)
	}

	// --- Release the alert pin when finished ---
	must(adc.DisableInterrupt(adcdac.ADS1014SourceComparator)) // Release ALERT/RDY, (source) → error
}

//go:build tinygo

// ADS1115 demo example — TinyGo (Raspberry Pi Pico W).
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

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1115I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	d, err := adcdac.NewADS1115Full(conn) // Create ADS1115 Full driver, (conn) → (*ADS1115Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure the converter ---
	// ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
	// reads short while still averaging out most noise.
	must(d.SetGain(adcdac.ADS1115PGA4096))   // Set gain, (pga 0-5) → error
	must(d.SetDataRate(adcdac.ADS1115DR250)) // Set data rate, (dr 0-7) → error

	// --- Read the inputs ---
	// Single-shot mode powers the chip down between conversions.
	for i := 0; i < 3; i++ {
		for ch := uint8(0); ch < 4; ch++ {
			raw, err := d.ReadRaw(ch) // Read raw code, (channel=0) → (int16, error)
			if err != nil {
				panic(err)
			}
			volts := float32(raw) * d.LSB()
			println("AIN raw", raw)
			println("AIN volts (mV)", int32(volts*1000))
		}
		time.Sleep(1000 * time.Millisecond)
	}

	// --- Arm a window comparator ---
	// ALERT/RDY asserts when the input leaves 0.5 V ... 3.0 V for two
	// consecutive conversions; the latch holds it until ClearAlert().
	must(d.SetThresholds(0.5, 3.0))                                                                            // Set thresholds, (low V, high V) → error
	must(d.ConfigureComparator(adcdac.ADS1115CompWindow, adcdac.ADS1115ActiveLow, true, adcdac.ADS1115Queue2)) // Configure comparator, (mode, polarity, latch, queue) → error
	must(d.StartContinuous(adcdac.ADS1115MuxAIN0GND))                                                          // Start continuous, (mux=MuxAIN0GND 0-7) → error

	// --- Report out-of-range events ---
	// With an InputPin on ALERT/RDY the callback fires on the edge; without
	// one a polling goroutine checks the register every 5 ms.
	must(d.OnInterrupt(func(status uint8) { // Subscribe, (callback) → error
		println("OUT OF RANGE", status)
		d.ClearAlert() // Clear alert, () → error
	}))
	time.Sleep(10000 * time.Millisecond)
	must(d.OffInterrupt())      // Unsubscribe, () → error
	must(d.DisableComparator()) // Disable comparator, () → error
	must(d.StopContinuous())    // Stop continuous, () → error

	// --- Bridge-sensor style differential read ---
	// The smallest range gives 7.8 uV per code for millivolt-level signals.
	must(d.SetGain(adcdac.ADS1115PGA0256))                // Set gain, (pga 0-5) → error
	diff, err := d.ReadDifferential(adcdac.ADS1115Diff23) // Read differential, (pair 0-3) → (float32 V, error)
	if err != nil {
		panic(err)
	}
	println("differential (mV)", int32(diff*1000))
}

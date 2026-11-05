//go:build linux && !tinygo

// ADS1114 demo example — Linux host.
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
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, adcdac.ADS1114I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	d, err := adcdac.NewADS1114Full(conn) // Create ADS1114 Full driver, (conn) → (*ADS1114Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure the converter ---
	// ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
	// reads short while still averaging out most noise.
	must(d.SetGain(adcdac.ADS1114PGA4096))   // Set gain, (pga 0-5) → error
	must(d.SetDataRate(adcdac.ADS1114DR250)) // Set data rate, (dr 0-7) → error

	// --- Read the inputs ---
	// Single-shot mode powers the chip down between conversions.
	for i := 0; i < 3; i++ {
		raw, err := d.ReadRaw() // Read raw code, () → (int16, error)
		if err != nil {
			panic(err)
		}
		volts := float32(raw) * d.LSB()
		fmt.Printf("raw %v\n", raw)
		fmt.Printf("volts %v\n", volts)
		time.Sleep(1000 * time.Millisecond)
	}

	// --- Arm a window comparator ---
	// ALERT/RDY asserts when the input leaves 0.5 V ... 3.0 V for two
	// consecutive conversions; the latch holds it until ClearAlert().
	must(d.SetThresholds(0.5, 3.0))                                                                            // Set thresholds, (low V, high V) → error
	must(d.ConfigureComparator(adcdac.ADS1114CompWindow, adcdac.ADS1114ActiveLow, true, adcdac.ADS1114Queue2)) // Configure comparator, (mode, polarity, latch, queue) → error
	must(d.StartContinuous())                                                                                  // Start continuous, () → error

	// --- Report out-of-range events ---
	// With an InputPin on ALERT/RDY the callback fires on the edge; without
	// one a polling goroutine checks the register every 5 ms.
	must(d.OnInterrupt(func(status uint8) { // Subscribe, (callback) → error
		fmt.Printf("OUT OF RANGE %v\n", status)
		d.ClearAlert() // Clear alert, () → error
	}))
	time.Sleep(10000 * time.Millisecond)
	must(d.OffInterrupt())      // Unsubscribe, () → error
	must(d.DisableComparator()) // Disable comparator, () → error
	must(d.StopContinuous())    // Stop continuous, () → error
}

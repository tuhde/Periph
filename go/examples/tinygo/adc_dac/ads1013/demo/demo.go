//go:build tinygo

// ADS1013 demo example — TinyGo (Raspberry Pi Pico W).
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

// Battery-voltage logger: the cell is read through a 2:1 resistor divider
// (AIN0, AIN1 to GND).
const (
	dividerRatio = 2.0
	logSamples   = 10
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1013I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	adc, err := adcdac.NewADS1013Full(conn) // Create ADS1013 Full driver, (conn) → (*ADS1013Full, error)
	must(err)

	// --- Slow, low-noise single-shot logging ---
	// 128 SPS gives the lowest noise; one conversion per second keeps the chip
	// in power-down in between.
	must(adc.SetDataRate(adcdac.ADS1013DR128)) // Set data rate, (dr 0–6) → error
	var low, high float32
	for i := 0; i < logSamples; i++ {
		raw, err := adc.ReadRaw() // Read raw code, () → (int16, error) (−2048…2047)
		must(err)
		v := float32(raw) * adc.LSB() // Read code size, () → float32 V
		battery := v * dividerRatio
		if i == 0 || battery < low {
			low = battery
		}
		if i == 0 || battery > high {
			high = battery
		}
		println("raw", raw)
		println("battery (mV)", int32((battery)*1000))
		println("min (mV)", int32((low)*1000))
		println("max (mV)", int32((high)*1000))
		time.Sleep(1000 * time.Millisecond)
	}

	// --- Fast continuous averaging ---
	// At 3300 SPS in continuous mode, 100 samples take about 30 ms.
	must(adc.SetDataRate(adcdac.ADS1013DR3300)) // Set data rate, (dr 0–6) → error
	must(adc.StartContinuous())                 // Start continuous conversion, () → error
	time.Sleep(10 * time.Millisecond)
	var total float32
	for i := 0; i < 100; i++ {
		v, err := adc.ReadContinuous() // Read latest voltage, () → (float32 V, error)
		must(err)
		total += v
		time.Sleep(time.Millisecond)
	}
	must(adc.StopContinuous()) // Stop continuous conversion, () → error
	println("average battery (mV)", int32((total/100*dividerRatio)*1000))
}

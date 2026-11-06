//go:build tinygo

// ADS1113 demo example — TinyGo (Raspberry Pi Pico W).
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
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1113I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	d, err := adcdac.NewADS1113Full(conn) // Create ADS1113 Full driver, (conn) → (*ADS1113Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure for low-noise slow sampling ---
	// 8 SPS gives the longest integration time per conversion, which
	// suppresses mains and switching noise on a slowly varying voltage.
	must(d.SetDataRate(adcdac.ADS1113DR8)) // Set data rate, (dr 0-7) → error

	// --- Log single-shot readings ---
	// Each ReadVoltage() wakes the chip, converts and powers down again.
	for i := 0; i < 5; i++ {
		v, err := d.ReadVoltage() // Read voltage, () → (float32 V, error)
		if err != nil {
			panic(err)
		}
		println("voltage (mV)", int32(v*1000))
		time.Sleep(1000 * time.Millisecond)
	}

	// --- Average continuous readings ---
	// Back-to-back conversions averaged over 1.25 s reduce random noise.
	must(d.StartContinuous()) // Start continuous, () → error
	time.Sleep(200 * time.Millisecond)
	var acc float32
	for i := 0; i < 10; i++ {
		cv, err := d.ReadContinuous() // Read continuous, () → (float32 V, error)
		if err != nil {
			panic(err)
		}
		acc += cv
		time.Sleep(125 * time.Millisecond)
	}
	must(d.StopContinuous()) // Stop continuous, () → error
	avg := acc / 10
	println("average", avg)
}

//go:build tinygo

// ADS1013 complete example — TinyGo (Raspberry Pi Pico W).
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
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1013I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	adc, err := adcdac.NewADS1013Full(conn) // Create ADS1013 Full driver, (conn) → (*ADS1013Full, error)
	// no bus traffic; fixed ±2.048 V, 1600 SPS
	must(err)

	raw, err := adc.ReadRaw() // Read raw code, () → (int16, error) (−2048…2047)
	// single-shot AIN0 − AIN1, polls OS until idle
	must(err)
	v, err := adc.ReadVoltage() // Read voltage, () → (float32 V, error)
	// raw × 0.001
	must(err)
	println("raw", raw)
	println("voltage (mV)", int32((v)*1000))
	println("full scale (mV)", int32((adc.FullScale())*1000)) // Read full-scale range, () → float32 V
	// always 2.048
	println("lsb (mmV)", int32((adc.LSB()*1000)*1000)) // Read code size, () → float32 V
	// always 0.001

	must(adc.SetDataRate(adcdac.ADS1013DR250)) // Set data rate, (dr 0–6) → error
	// DR250 = 250 SPS
	println("dr code", adc.GetDataRate()) // Read data-rate code, () → uint8
	// cached DR[2:0]
	println("sps", adc.DataRateSPS()) // Read data rate, () → uint16 SPS
	// table lookup of the DR code

	must(adc.StartContinuous()) // Start continuous conversion, () → error
	// MODE = 0 at the current data rate
	time.Sleep(20 * time.Millisecond)
	craw, err := adc.ReadContinuousRaw() // Read latest code, () → (int16, error)
	// reads CONVERSION without touching Config
	must(err)
	println("continuous raw", craw)
	cv, err := adc.ReadContinuous() // Read latest voltage, () → (float32 V, error)
	// raw × LSB()
	must(err)
	println("continuous (mV)", int32((cv)*1000))
	must(adc.StopContinuous()) // Stop continuous conversion, () → error
	// MODE = 1, back to single-shot
	busy, err := adc.IsConverting() // Check conversion in progress, () → (bool, error)
	// CONFIG.OS = 0 (single-shot only)
	must(err)
	println("converting", busy)
	cfg, err := adc.ReadConfig() // Read CONFIG register, () → (uint16, error)
	// raw 16-bit register
	must(err)
	println("config", cfg)
	must(adc.Reset()) // Reset to power-up defaults, () → error
	// rewrites CONFIG = 0x8583
}

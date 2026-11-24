//go:build tinygo

// MPL3115A2 demo — TinyGo / Raspberry Pi Pico W.
//
// Weather and altitude station: sea-level reference, barometer and altimeter readings,
// FIFO statistics and a temperature threshold on INT1.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil) // Create I2C connection, (i2c, addr=0x60, ) → (*I2CConnection)

	chip := pressure.NewMPL3115A2Full(conn) // Create MPL3115A2 driver, (connection) → *MPL3115A2Full
	chk(chip.Init())                        // Reset and apply defaults, () → error

	// --- Weather and altitude station ---
	// Sets the local sea-level pressure, takes three barometer readings, switches to
	// altimeter mode for three altitude readings, collects 16 samples in the circular
	// FIFO and prints their minimum, maximum and mean, then arms a temperature
	// threshold on INT1 and reports the captured extremes.
	chk(chip.SetSeaLevelPressure(101325)) // Set the sea-level reference, (pa float32) → error
	for i := 0; i < 3; i++ {
		p, t, err := chip.Read() // Read pressure and temperature, () → (float32 Pa, float32 °C, error)
		chk(err)
		fmt.Printf("%.2f Pa  %.2f C\n", p, t)
		time.Sleep(5 * time.Second)
	}
	for i := 0; i < 3; i++ {
		alt, err := chip.ReadAltitude() // Read altitude, () → (float32 m, error)
		chk(err)
		fmt.Printf("altitude %.2f m\n", alt)
	}
	chk(chip.SetMode(false))                                // Back to barometer mode, (altimeter bool) → error
	chk(chip.SetOversample(16))                             // Faster conversions, (ratio) → error
	chk(chip.FIFOSetup(pressure.MPL3115A2FIFOCircular, 16)) // Circular FIFO, watermark 16, (mode, watermark) → error
	chk(chip.StartContinuous(0))                            // One sample per second, (stepExp 0-15) → error
	for {
		_, wm, _, err := chip.FIFOStatus() // Wait for the watermark flag, () → (overflow, watermark bool, count uint8, error)
		chk(err)
		if wm {
			break
		}
		time.Sleep(time.Second)
	}
	samples, err := chip.FIFORead() // Drain the FIFO, () → ([]MPL3115A2Sample, error)
	chk(err)
	lo, hi, sum := samples[0].Value, samples[0].Value, float32(0)
	for _, s := range samples {
		if s.Value < lo {
			lo = s.Value
		}
		if s.Value > hi {
			hi = s.Value
		}
		sum += s.Value
	}
	fmt.Printf("n=%d min %.2f max %.2f mean %.2f Pa\n", len(samples), lo, hi, sum/float32(len(samples)))
	chk(chip.SetTarget(0, 0, int8(samples[len(samples)-1].TemperatureC)+1, 0)) // Temperature target, (value, window float32, temperatureC int8, temperatureWindow uint8) → error
	chk(chip.EnableInterrupt(pressure.MPL3115A2SourceTTH, 1))                  // Route temperature threshold to INT1, (source, pin) → error
	src, err := chip.PollInterrupt()                                           // Read INT_SOURCE, () → (uint8, error)
	chk(err)
	fmt.Printf("INT_SOURCE 0x%02X\n", src)
	chk(chip.StopContinuous())                        // Back to STANDBY, () → error
	chk(chip.FIFOSetup(pressure.MPL3115A2FIFOOff, 0)) // FIFO off, (mode, watermark) → error
	mm, err := chip.ReadMinMax()                      // Captured extremes, () → (MPL3115A2MinMax, error)
	chk(err)
	fmt.Printf("min %.2f Pa max %.2f Pa\n", mm.PMin, mm.PMax)
}

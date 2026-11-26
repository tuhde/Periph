//go:build tinygo

// SPL06 demo example — TinyGo / Raspberry Pi Pico W.
//
// Barometric altimeter and variometer: altitude, oversampling noise, background
// mode, FIFO, interrupt, manual compensation.
package main

import (
	"fmt"
	"machine"
	"math"
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

	conn := connection.NewI2CConnection(i2c, 0x77, nil, nil) // Create I2C connection, (i2c, addr=0x77) → (*I2CConnection)

	chip, err := pressure.NewSPL06Full(conn) // Create SPL06 driver, (connection) → (*SPL06Full, error)
	chk(err)

	// --- Identity and calibration ---
	// Init checks ID = 0x10; the nine coefficients are factory-trimmed per
	// part and drive every compensated value below.
	chk(chip.Init())                                          // Check ID, read coefficients, apply defaults, () → error
	fmt.Printf("ID 0x10 verified %+v\n", chip.Coefficients()) // Decoded calibration, () → SPL06Coefficients

	// --- Altimeter: 20 s at 1 Hz, altitude relative to the first reading ---
	// The first pressure becomes the reference level, so the altitude column
	// shows how far the sensor moved since start (about 12 Pa per metre).
	p0, err := chip.ReadPressure() // Reference pressure, () → (float64 Pa, error)
	chk(err)
	for i := 0; i < 20; i++ {
		m, err := chip.Read() // Temperature and pressure, () → (SPL06Measurement, error)
		chk(err)
		t, err := chip.ReadTemperature() // Temperature only, () → (float64 °C, error)
		chk(err)
		h, err := chip.Altitude(p0) // Altitude vs. reference, (seaLevelPa) → (float64 m, error)
		chk(err)
		fmt.Printf("%d s  %.2f C (%.2f C)  %.2f Pa  %.2f m\n", i, m.TemperatureC, t, m.PressurePa, h)
		time.Sleep(time.Second)
	}

	// --- Oversampling: noise and measurement time at 1x, 16x and 128x ---
	// More oversampling means less noise but longer measurements; above 8x
	// the driver sets P_SHIFT automatically.
	for _, osr := range []int{1, 16, 128} {
		chk(chip.SetPressureConfig(osr, 1)) // Pressure oversampling, (oversampling, rate) → error
		var v [10]float64
		start := time.Now()
		for j := range v {
			v[j], err = chip.ReadPressure() // Pressure, () → (float64 Pa, error)
			chk(err)
		}
		per := time.Since(start) / 10
		mean, variance := 0.0, 0.0
		for _, x := range v {
			mean += x / 10
		}
		for _, x := range v {
			variance += (x - mean) * (x - mean) / 10
		}
		fmt.Printf("%dx: mean %.2f Pa  sd %.2f Pa  %v per reading\n", osr, mean, math.Sqrt(variance), per)
	}

	// --- Variometer: background mode at 8 Hz ---
	// The chip measures on its own; ReadContinuous only fetches the latest
	// results, so the host never waits for a conversion it started itself.
	chk(chip.SetPressureConfig(16, 8))    // 16x, 8 Hz, (oversampling, rate) → error
	chk(chip.SetTemperatureConfig(1, 8))  // 1x, 8 Hz, (oversampling, rate) → error
	chk(chip.StartContinuous(true, true)) // Background mode, (pressure, temperature) → error
	last := math.NaN()
	for i := 0; i < 16; i++ {
		c, err := chip.ReadContinuous() // Latest results, () → (SPL06Measurement, error)
		chk(err)
		if !math.IsNaN(last) {
			fmt.Printf("%.2f Pa  climb %.2f m/s\n", c.PressurePa, -(c.PressurePa-last)/12*8)
		}
		last = c.PressurePa
	}
	pr, tr, err := chip.DataReady() // Ready flags, () → (bool, bool, error)
	chk(err)
	fmt.Println("ready flags", pr, tr)
	chk(chip.Stop()) // Standby, () → error

	// --- FIFO: let the chip collect 32 results, then read them in one go ---
	// Each entry's LSB marks it as pressure or temperature; the host can sleep
	// while the chip fills the FIFO (32 entries = 2 s at 8 Hz for both).
	chk(chip.FlushFIFO())                 // Empty the FIFO, () → error
	chk(chip.EnableFIFO())                // FIFO on, () → error
	chk(chip.StartContinuous(true, true)) // Background mode, (pressure, temperature) → error
	for i := 0; i < 50; i++ {
		_, full, err := chip.FIFOStatus() // Wait for full, () → (bool empty, bool full, error)
		chk(err)
		if full {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	chk(chip.Stop())                // Standby, () → error
	entries, err := chip.ReadFIFO() // Drain FIFO, () → ([]SPL06FifoEntry, error)
	chk(err)
	sum, np := 0.0, 0
	for _, e := range entries {
		if e.Kind == pressure.SPL06KindPressure {
			sum += e.Value
			np++
		}
	}
	if np > 0 {
		sum /= float64(np)
	}
	fmt.Printf("%d FIFO entries, %d pressure, mean %.2f Pa\n", len(entries), np, sum)
	chk(chip.DisableFIFO()) // FIFO off, () → error

	// --- Data-ready interrupt ---
	// SDO doubles as the interrupt line; the callback receives the INT_STS bits.
	// Without an INT pin on the connection the driver polls INT_STS instead.
	chk(chip.EnableInterrupt(pressure.SPL06SourcePressure)) // Pressure-ready interrupt, (source) → error
	_, err = chip.PollInterrupt()                           // Clear stale status, () → (uint8, error)
	chk(err)
	chk(chip.OnInterrupt(func(status uint8) { fmt.Printf("interrupt 0x%02X\n", status) })) // Subscribe, (callback) → error
	chk(chip.StartContinuous(true, true))                                                  // Background mode, (pressure, temperature) → error
	time.Sleep(2 * time.Second)
	chk(chip.OffInterrupt())                                 // Unsubscribe, () → error
	chk(chip.DisableInterrupt(pressure.SPL06SourcePressure)) // Disable source, (source) → error
	chk(chip.Stop())                                         // Standby, () → error

	// --- Compensation by hand ---
	// Compensate turns raw register values into SI units with the stored
	// coefficients; useful for logging raw data and converting it later.
	chk(chip.SetPressureConfig(16, 1))   // Back to 16x, (oversampling, rate) → error
	chk(chip.SetTemperatureConfig(1, 1)) // Back to 1x, (oversampling, rate) → error
	praw, traw, err := chip.ReadRaw()    // Raw results, () → (int32, int32, error)
	chk(err)
	c, err := chip.Compensate(praw, traw, 16, 1) // Compensate, (praw, traw, pOversampling, tOversampling) → (SPL06Measurement, error)
	chk(err)
	fmt.Printf("raw %d %d -> %.2f C %.2f Pa\n", praw, traw, c.TemperatureC, c.PressurePa)

	// --- Wrap up ---
	// Standby, empty FIFO and a soft reset leave the chip as after power-up
	// (plus the driver defaults).
	chk(chip.Stop())      // Standby, () → error
	chk(chip.FlushFIFO()) // Empty the FIFO, () → error
	chk(chip.Reset())     // Soft reset and defaults, () → error
}

//go:build tinygo

// VL6180X complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/tof"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnectionWithWidth(machine.I2C0, tof.VL6180XI2CAddress, 2, nil, nil) // Create I2C connection, (i2c, addr=0x29, regBytes=2) → *I2CConnection
	defer conn.Close()

	d, err := tof.NewVL6180XFull(conn) // Create VL6180X Full driver, (conn) → (*VL6180XFull, error)
	// init: boot wait, private settings only when fresh out of reset, recommended settings
	must(err)

	mm, err := d.ReadRange() // Single-shot range, () → (uint8 mm, error)
	// ErrVL6180XRange on a non-zero range error code; see LastError()
	println("range", mm, err)
	mm, code, err := d.ReadRangeStatus() // Range and error code, () → (uint8 mm, uint8 error, error)
	// never fails on a range error; 0 = valid
	must(err)
	println("range status", mm, code)
	raw, err := d.ReadAlsRaw() // Single-shot ALS count, () → (uint16, error)
	must(err)
	lux, err := d.ReadLux() // Single-shot ALS in lux, () → (float32 lux, error)
	// 0.32 lux/count at gain 1, 100 ms
	must(err)
	println("als", raw, "lux x100", int(lux*100))

	must(d.StartContinuousRange(50)) // Start continuous ranging, (periodMs) → error
	// 10-2550 ms in 10 ms steps
	mm, err = d.ReadRangeContinuous() // Wait for next sample, () → (uint8 mm, error)
	println("continuous", mm, err)
	must(d.StopContinuousRange())   // Stop continuous ranging, () → error
	must(d.StartContinuousAls(100)) // Start continuous ALS, (periodMs) → error
	must(d.StopContinuousAls())     // Stop continuous ALS, () → error
	must(d.SetInterleaved(false))   // Interleaved ALS + range, (on) → error

	must(d.SetAlsGain(6)) // ALS gain code, (code) → error
	// 0 = 20 ... 6 = 1, 7 = 40
	must(d.SetAlsIntegrationTime(100)) // ALS integration time, (ms) → error
	// 1-512 ms
	must(d.SetAlsScaler(1))             // ALS result scaler, (scaler) → error
	must(d.SetRangeThresholds(30, 80))  // Range thresholds, (low, high) → error
	must(d.SetAlsThresholds(100, 5000)) // ALS thresholds, (low, high) → error
	must(d.SetMaxConvergenceTime(49))   // Max convergence time, (ms) → error
	must(d.SetAveragingPeriod(48))      // Averaging period, (units) → error
	must(d.SetRangeOffset(0))           // Part-to-part offset, (mm) → error
	off, err := d.GetRangeOffset()      // Read the offset, () → (int8 mm, error)
	must(err)
	println("offset", off)
	must(d.SetCrosstalkRate(0)) // Crosstalk rate, (rateMcps) → error
	must(d.RecalibrateVHV())    // Recalibrate VHV, () → error

	id, err := d.ModelID() // Model ID, () → (uint8, error)
	// always 0xB4
	must(err)
	major, minor, err := d.Revision() // Model revision, () → (uint8, uint8, error)
	must(err)
	println("model", id, "revision", major, minor)
	hist, err := d.ReadHistory() // History buffer, () → ([8]uint16, error)
	must(err)
	println("history", hist[0], hist[1])

	must(d.EnableInterrupt(tof.VL6180XSourceRange, tof.VL6180XModeOutOfWindow)) // Enable an interrupt, (source, mode) → error
	pending, err := d.PollInterrupt()                                           // Read and clear, () → (uint8 SOURCE_* mask, error)
	must(err)
	println("pending", pending)
	must(d.OnInterrupt(func(status uint8) { println("irq", status) })) // Subscribe, (callback) → error
	// falling edge on conn.IntPin(), else a 5 ms polling pin
	time.Sleep(100 * time.Millisecond)
	must(d.OffInterrupt())                           // Unsubscribe, () → error
	must(d.DisableInterrupt(tof.VL6180XSourceRange)) // Disable an interrupt, (source) → error
	must(d.EnableInterrupt(tof.VL6180XSourceRange, tof.VL6180XModeNewSampleReady))
	// must(d.SetAddress(0x30)) // Change address (volatile), (address) → error
}

//go:build tinygo

// VL6180X demo example — TinyGo (Raspberry Pi Pico W).
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
	must(err)

	// --- Identification ---
	id, err := d.ModelID() // Model ID, () → (uint8, error)
	must(err)
	major, minor, err := d.Revision() // Model revision, () → (uint8, uint8, error)
	must(err)
	println("model", id, "revision", major, minor)

	// --- Single-shot ranging at 5 Hz for 10 s ---
	for i := 0; i < 50; i++ {
		if mm, err := d.ReadRange(); err == nil { // Single-shot range, () → (uint8 mm, error)
			println("mm", mm)
		}
		time.Sleep(200 * time.Millisecond)
	}

	// --- Continuous ranging at 50 ms: move a hand towards the sensor ---
	must(d.StartContinuousRange(50)) // Start continuous ranging, (periodMs) → error
	for i := 0; i < 40; i++ {
		mm, err := d.ReadRangeContinuous() // Wait for next sample, () → (uint8 mm, error)
		println("mm", mm, err)
	}
	must(d.StopContinuousRange()) // Stop continuous ranging, () → error
	time.Sleep(200 * time.Millisecond)

	// --- Out-of-window interrupt for 30-80 mm ---
	must(d.SetRangeThresholds(30, 80))                                          // Range thresholds, (low, high) → error
	must(d.EnableInterrupt(tof.VL6180XSourceRange, tof.VL6180XModeOutOfWindow)) // Enable an interrupt, (source, mode) → error
	must(d.StartContinuousRange(50))
	for i := 0; i < 50; i++ {
		if s, _ := d.PollInterrupt(); s&tof.VL6180XSourceRange != 0 { // Read and clear, () → (uint8 SOURCE_* mask, error)
			println("range out of window")
		}
		time.Sleep(100 * time.Millisecond)
	}
	must(d.StopContinuousRange())
	time.Sleep(200 * time.Millisecond)
	must(d.EnableInterrupt(tof.VL6180XSourceRange, tof.VL6180XModeNewSampleReady))

	// --- Light meter at gain 1 and 20 ---
	for _, code := range []uint8{6, 0} {
		must(d.SetAlsGain(code)) // ALS gain code, (code) → error
		lux, err := d.ReadLux()  // Single-shot ALS in lux, () → (float32 lux, error)
		must(err)
		println("gain code", code, "lux x100", int(lux*100))
	}
	must(d.SetAlsGain(6))

	// --- Interleaved ALS + range for 5 s ---
	must(d.SetInterleaved(true))    // Interleaved ALS + range, (on) → error
	must(d.StartContinuousAls(100)) // Start continuous ALS, (periodMs) → error
	time.Sleep(5 * time.Second)
	must(d.StopContinuousAls()) // Stop continuous ALS, () → error
	must(d.SetInterleaved(false))

	// --- Calibration and diagnostics ---
	must(d.SetRangeOffset(0))          // Part-to-part offset, (mm) → error
	must(d.SetCrosstalkRate(0))        // Crosstalk rate, (rateMcps) → error
	must(d.SetAlsIntegrationTime(100)) // ALS integration time, (ms) → error
	must(d.SetAlsScaler(1))            // ALS result scaler, (scaler) → error
	must(d.RecalibrateVHV())           // Recalibrate VHV, () → error
	raw, err := d.ReadAlsRaw()         // Single-shot ALS count, () → (uint16, error)
	must(err)
	off, err := d.GetRangeOffset() // Read the offset, () → (int8 mm, error)
	must(err)
	println("als raw", raw, "offset", off)
	mm, code, err := d.ReadRangeStatus() // Range and error code, () → (uint8, uint8, error)
	must(err)
	hist, err := d.ReadHistory() // History buffer, () → ([8]uint16, error)
	must(err)
	println("last range", mm, "error", code, "history", hist[0])

	// --- Change the device address and back (volatile) ---
	must(d.SetAddress(0x30)) // Change address, (address) → error
	conn2 := connection.NewI2CConnectionWithWidth(machine.I2C0, 0x30, 2, nil, nil)
	defer conn2.Close()
	moved, err := tof.NewVL6180XFull(conn2)
	must(err)
	id, err = moved.ModelID()
	must(err)
	println("on 0x30: model", id)
	must(moved.SetAddress(tof.VL6180XI2CAddress))
}

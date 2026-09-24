//go:build tinygo

// VL53L0X complete example — TinyGo (Raspberry Pi Pico W). Exercises every method in the Full
// API, and finally moves the sensor to another I²C address and back to 0x29.
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
	conn := connection.NewI2CConnection(machine.I2C0, tof.VL53L0XI2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x29) → *I2CConnection
	defer conn.Close()

	s, err := tof.NewVL53L0XFull(conn) // Create VL53L0X Full driver, (conn) → (*VL53L0XFull, error)
	// runs init: ID check, tuning, SPADs, VHV + phase calibration
	must(err)

	id, _ := s.ModelID() // Read model ID, () → (uint8, error)
	// IDENTIFICATION_MODEL_ID, always 0xEE
	rev, _ := s.RevisionID() // Read revision ID, () → (uint8, error)
	// IDENTIFICATION_REVISION_ID, 0x10 on current silicon
	println("model", id, "revision", rev)

	d, err := s.Distance() // Measure distance, () → (uint16 mm, error)
	// single shot; blocks for about one timing budget
	must(err)
	println("distance (mm)", d, "valid", s.RangeValid()) // Check last measurement, () → bool
	// device range status == 11 (range complete)
	println("range status", s.RangeStatus()) // Read last range status, () → uint8 0–15
	// 11 = valid, 4 = no target
	m, err := s.ReadMeasurement() // Read result block, () → (VL53L0XMeasurement, error)
	// distance, status, signal/ambient MCPS, SPAD count
	must(err)
	println("signal (mMCPS)", int32(m.SignalRateMCPS*1000), "ambient (mMCPS)", int32(m.AmbientRateMCPS*1000))

	must(s.StartContinuous(0)) // Start continuous ranging, (periodMs=0 ms) → error
	// 0 = back-to-back measurements
	for i := 0; i < 5; i++ {
		r, err := s.ReadContinuous() // Read next continuous result, () → (uint16 mm, error)
		// waits for a fresh data-ready, then clears it
		must(err)
		println("continuous (mm)", r)
	}
	must(s.StopContinuous()) // Stop continuous ranging, () → error
	// does not wait for a running measurement

	must(s.StartContinuous(100)) // Start continuous ranging, (periodMs=0 ms) → error
	// timed mode: one measurement every 100 ms
	for ready, _ := s.DataReady(); !ready; ready, _ = s.DataReady() { // Check for a result, () → (bool, error)
		// RESULT_INTERRUPT_STATUS bits 2:0 non-zero
		time.Sleep(10 * time.Millisecond)
	}
	m, _ = s.ReadMeasurement() // Read result block, () → (VL53L0XMeasurement, error)
	// non-blocking; clears the interrupt
	println("timed (mm)", m.DistanceMM)
	must(s.StopContinuous()) // Stop continuous ranging, () → error
	// back to software standby

	budget, _ := s.TimingBudget() // Read timing budget, () → (uint32 µs, error)
	// computed from the sequence-step timeouts
	println("budget (us)", budget)
	must(s.SetTimingBudget(50000)) // Set timing budget, (budgetUs µs) → error
	// longer budget = lower noise, ≥ 20000 µs
	limit, _ := s.SignalRateLimit() // Read signal-rate limit, () → (float32 MCPS, error)
	// 9.7 fixed point
	println("signal limit (mMCPS)", int32(limit*1000))
	must(s.SetSignalRateLimit(0.1)) // Set signal-rate limit, (limitMcps MCPS) → error
	// lower = longer range, more noise
	must(s.SetVcselPulsePeriod(tof.VL53L0XPreRange, 18)) // Set VCSEL period, (periodType, pclks) → error
	// pre-range 12/14/16/18; redoes phase calibration
	must(s.SetVcselPulsePeriod(tof.VL53L0XFinalRange, 14)) // Set VCSEL period, (periodType, pclks) → error
	// final-range 8/10/12/14
	pre, _ := s.VcselPulsePeriod(tof.VL53L0XPreRange) // Read VCSEL period, (periodType) → (uint8 PCLKs, error)
	// (reg + 1) × 2
	fin, _ := s.VcselPulsePeriod(tof.VL53L0XFinalRange) // Read VCSEL period, (periodType) → (uint8 PCLKs, error)
	// (reg + 1) × 2
	println("vcsel (PCLKs)", pre, fin)
	must(s.SetProfile(tof.VL53L0XProfileDefault)) // Apply ranging profile, (profile) → error
	// 0.25 MCPS, 14/10 PCLKs, 33 ms

	original, _ := s.Offset() // Read range offset, () → (float32 mm, error)
	// NVM factory value, 0.25 mm steps
	must(s.SetOffset(original - 5)) // Set range offset, (offsetMm mm) → error
	// volatile override, −512.0 to 511.75 mm
	off, _ := s.Offset() // Read range offset, () → (float32 mm, error)
	// 12-bit two's complement × 0.25
	println("offset (0.01 mm)", int32(off*100))
	must(s.SetOffset(original)) // Set range offset, (offsetMm mm) → error
	// restore the factory value
	must(s.SetCrosstalkCompensation(0)) // Set crosstalk compensation, (rateMcps MCPS) → error
	// 0 = compensation off

	must(s.Recalibrate()) // Rerun reference calibration, () → error
	// VHV + phase; needed after a > 8 °C change

	must(s.SetInterruptThresholds(100, 800)) // Set distance thresholds, (lowMm mm, highMm mm) → error
	// 2 mm resolution
	lo, hi, _ := s.InterruptThresholds() // Read distance thresholds, () → (uint16 mm, uint16 mm, error)
	// decoded from SYSTEM_THRESH_LOW/HIGH
	println("thresholds (mm)", lo, hi)
	must(s.EnableInterrupt(tof.VL53L0XSourceOutOfWindow)) // Select interrupt source, (source) → error
	// replaces the active source (mutually exclusive)
	must(s.DisableInterrupt(tof.VL53L0XSourceOutOfWindow)) // Disable interrupt source, (source) → error
	// only if it is the active one
	must(s.EnableInterrupt(tof.VL53L0XSourceNewSampleReady)) // Select interrupt source, (source) → error
	// back to the default data-ready source

	must(s.OnInterrupt(func(status uint8) { println("interrupt, source", status) })) // Subscribe to GPIO1, (callback) → error
	// status is read and cleared before the callback
	must(s.StartContinuous(200)) // Start continuous ranging, (periodMs=0 ms) → error
	// timed mode feeds the subscription
	time.Sleep(time.Second)
	must(s.StopContinuous()) // Stop continuous ranging, () → error
	// no more samples
	must(s.OffInterrupt()) // Unsubscribe, () → error
	// detaches the pin handler or stops the polling goroutine
	st, _ := s.PollInterrupt() // Read and clear status, () → (uint8, error)
	// SOURCE_* value that fired, 0 = nothing pending
	println("pending", st)

	must(s.SetAddress(0x30)) // Change I²C address, (address) → error
	// volatile; this driver instance is now unusable
	moved := connection.NewI2CConnection(machine.I2C0, 0x30, nil, nil) // Create I2C connection, (i2c, addr=0x30) → *I2CConnection
	defer moved.Close()
	s2, err := tof.NewVL53L0XFull(moved) // Create VL53L0X Full driver, (conn) → (*VL53L0XFull, error)
	// re-init at the new address is safe
	must(err)
	d, _ = s2.Distance() // Measure distance, () → (uint16 mm, error)
	// same sensor, new address
	println("at 0x30 (mm)", d)
	must(s2.SetAddress(tof.VL53L0XI2CAddress)) // Change I²C address, (address) → error
	// back to the power-on 0x29
}

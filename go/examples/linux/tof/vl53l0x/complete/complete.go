//go:build linux && !tinygo

// VL53L0X complete example — Linux host. Exercises every method in the Full
// API, and finally moves the sensor to another I²C address and back to 0x29.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/tof"
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
	must(err)

	conn, err := connection.NewI2CConnection(bus, tof.VL53L0XI2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x29) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	s, err := tof.NewVL53L0XFull(conn) // Create VL53L0X Full driver, (conn) → (*VL53L0XFull, error)
	// runs init: ID check, tuning, SPADs, VHV + phase calibration
	must(err)

	id, _ := s.ModelID() // Read model ID, () → (uint8, error)
	// IDENTIFICATION_MODEL_ID, always 0xEE
	rev, _ := s.RevisionID() // Read revision ID, () → (uint8, error)
	// IDENTIFICATION_REVISION_ID, 0x10 on current silicon
	fmt.Printf("model 0x%02X, revision 0x%02X\n", id, rev)

	d, err := s.Distance() // Measure distance, () → (uint16 mm, error)
	// single shot; blocks for about one timing budget
	must(err)
	fmt.Println("distance", d, "mm, valid", s.RangeValid()) // Check last measurement, () → bool
	// device range status == 11 (range complete)
	fmt.Println("range status", s.RangeStatus()) // Read last range status, () → uint8 0–15
	// 11 = valid, 4 = no target
	m, err := s.ReadMeasurement() // Read result block, () → (VL53L0XMeasurement, error)
	// distance, status, signal/ambient MCPS, SPAD count
	must(err)
	fmt.Printf("signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs\n", m.SignalRateMCPS, m.AmbientRateMCPS, m.EffectiveSpadCount)

	must(s.StartContinuous(0)) // Start continuous ranging, (periodMs=0 ms) → error
	// 0 = back-to-back measurements
	for i := 0; i < 5; i++ {
		r, err := s.ReadContinuous() // Read next continuous result, () → (uint16 mm, error)
		// waits for a fresh data-ready, then clears it
		must(err)
		fmt.Println("continuous", r, "mm")
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
	fmt.Println("timed", m.DistanceMM, "mm")
	must(s.StopContinuous()) // Stop continuous ranging, () → error
	// back to software standby

	budget, _ := s.TimingBudget() // Read timing budget, () → (uint32 µs, error)
	// computed from the sequence-step timeouts
	fmt.Println("budget", budget, "us")
	must(s.SetTimingBudget(50000)) // Set timing budget, (budgetUs µs) → error
	// longer budget = lower noise, ≥ 20000 µs
	limit, _ := s.SignalRateLimit() // Read signal-rate limit, () → (float32 MCPS, error)
	// 9.7 fixed point
	fmt.Printf("signal limit %.3f MCPS\n", limit)
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
	fmt.Println("vcsel", pre, fin, "PCLKs")
	must(s.SetProfile(tof.VL53L0XProfileDefault)) // Apply ranging profile, (profile) → error
	// 0.25 MCPS, 14/10 PCLKs, 33 ms

	original, _ := s.Offset() // Read range offset, () → (float32 mm, error)
	// NVM factory value, 0.25 mm steps
	must(s.SetOffset(original - 5)) // Set range offset, (offsetMm mm) → error
	// volatile override, −512.0 to 511.75 mm
	off, _ := s.Offset() // Read range offset, () → (float32 mm, error)
	// 12-bit two's complement × 0.25
	fmt.Println("offset", off, "mm")
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
	fmt.Println("thresholds", lo, hi, "mm")
	must(s.EnableInterrupt(tof.VL53L0XSourceOutOfWindow)) // Select interrupt source, (source) → error
	// replaces the active source (mutually exclusive)
	must(s.DisableInterrupt(tof.VL53L0XSourceOutOfWindow)) // Disable interrupt source, (source) → error
	// only if it is the active one
	must(s.EnableInterrupt(tof.VL53L0XSourceNewSampleReady)) // Select interrupt source, (source) → error
	// back to the default data-ready source

	must(s.OnInterrupt(func(status uint8) { fmt.Println("interrupt, source", status) })) // Subscribe to GPIO1, (callback) → error
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
	fmt.Println("pending", st)

	must(s.SetAddress(0x30)) // Change I²C address, (address) → error
	// volatile; this driver instance is now unusable
	moved, err := connection.NewI2CConnection(bus, 0x30, nil, nil) // Create I2C connection, (bus=1, addr=0x30) → (*I2CConnection, error)
	must(err)
	defer moved.Close()
	s2, err := tof.NewVL53L0XFull(moved) // Create VL53L0X Full driver, (conn) → (*VL53L0XFull, error)
	// re-init at the new address is safe
	must(err)
	d, _ = s2.Distance() // Measure distance, () → (uint16 mm, error)
	// same sensor, new address
	fmt.Println("at 0x30", d, "mm")
	must(s2.SetAddress(tof.VL53L0XI2CAddress)) // Change I²C address, (address) → error
	// back to the power-on 0x29
}

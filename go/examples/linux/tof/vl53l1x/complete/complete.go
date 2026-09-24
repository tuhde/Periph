//go:build linux && !tinygo

// VL53L1X complete example — Linux host. Exercises every method in the Full
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

	conn, err := connection.NewI2CConnection(bus, tof.VL53L1XI2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x29) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	s, err := tof.NewVL53L1XFull(conn) // Create VL53L1X Full driver, (conn) → (*VL53L1XFull, error)
	// runs init: boot poll, ID check, ULD default config
	must(err)

	id, _ := s.ModelID() // Read model ID, () → (uint8, error)
	// IDENTIFICATION__MODEL_ID, always 0xEA
	mt, _ := s.ModuleType() // Read module type, () → (uint8, error)
	// IDENTIFICATION__MODULE_TYPE, always 0xCC
	rev, _ := s.RevisionID() // Read revision ID, () → (uint8, error)
	// mask revision, 0x10
	fmt.Printf("model 0x%02X, module 0x%02X, revision 0x%02X\n", id, mt, rev)

	d, err := s.Distance() // Measure distance, () → (uint16 mm, error)
	// single shot; blocks for about one timing budget
	must(err)
	fmt.Println("distance", d, "mm, valid", s.RangeValid()) // Check last measurement, () → bool
	// mapped range status == 0
	fmt.Println("range status", s.RangeStatus()) // Read last range status, () → uint8
	// 0 = valid, 2 = signal fail, 4 = out of bounds
	m, err := s.ReadMeasurement() // Read result block, () → (VL53L1XMeasurement, error)
	// distance, status, signal/ambient MCPS, SPAD count
	must(err)
	fmt.Printf("signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs\n", m.SignalRateMCPS, m.AmbientRateMCPS, m.EffectiveSpadCount)

	mode, err := s.DistanceMode() // Read distance mode, () → (VL53L1XDistanceMode, error)
	// from PHASECAL_CONFIG__TIMEOUT_MACROP
	must(err)
	fmt.Println("long mode", mode == tof.VL53L1XDistanceModeLong)
	must(s.SetDistanceMode(tof.VL53L1XDistanceModeShort)) // Set distance mode, (mode) → error
	// ~1.3 m, robust in sunlight; keeps the budget
	budget, err := s.TimingBudget() // Read timing budget, () → (uint32 µs, error)
	// decoded from the range timeout A register
	must(err)
	fmt.Println("budget", budget, "us")
	must(s.SetTimingBudget(33000)) // Set timing budget, (budgetUs µs) → error
	// ULD table values 15000 (short only) … 500000
	d, err = s.Distance() // Measure distance, () → (uint16 mm, error)
	// 33 ms single shot
	must(err)
	fmt.Println("short mode", d, "mm")
	must(s.SetDistanceMode(tof.VL53L1XDistanceModeLong)) // Set distance mode, (mode) → error
	// back to up to 4 m in the dark
	must(s.SetTimingBudget(100000)) // Set timing budget, (budgetUs µs) → error
	// default 100 ms

	must(s.SetInterMeasurement(200)) // Set inter-measurement period, (periodMs ms) → error
	// must be ≥ the timing budget
	period, _ := s.InterMeasurement() // Read inter-measurement period, () → (uint32 ms, error)
	// oscillator ticks scaled by the PLL calibration
	fmt.Println("period", period, "ms")
	must(s.StartContinuous(200)) // Start continuous ranging, (periodMs=0 ms) → error
	// timed mode; 0 = as fast as the budget allows
	for i := 0; i < 5; i++ {
		r, err := s.ReadContinuous() // Read next continuous result, () → (uint16 mm, error)
		// waits for data ready, then clears it
		must(err)
		fmt.Println("continuous", r, "mm")
	}
	for {
		ready, err := s.DataReady() // Check for a result, () → (bool, error)
		// GPIO1 line asserted
		must(err)
		if ready {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	m, err = s.ReadMeasurement() // Read result block, () → (VL53L1XMeasurement, error)
	// non-blocking; clears the interrupt
	must(err)
	fmt.Println("record", m.DistanceMM, "mm")
	must(s.StopContinuous()) // Stop continuous ranging, () → error
	// does not wait for a running measurement
	time.Sleep(250 * time.Millisecond)

	limit, _ := s.SignalRateLimit() // Read signal-rate limit, () → (float32 MCPS, error)
	// 9.7 fixed point, default 1.0
	fmt.Printf("signal limit %.3f MCPS\n", limit)
	must(s.SetSignalRateLimit(0.5)) // Set signal-rate limit, (limitMcps MCPS) → error
	// lower = longer range, more noise
	must(s.SetSignalRateLimit(1.0)) // Set signal-rate limit, (limitMcps MCPS) → error
	// restore the default
	sigma, _ := s.SigmaThreshold() // Read sigma threshold, () → (uint16 mm, error)
	// 14.2 fixed point, default 90
	fmt.Println("sigma", sigma, "mm")
	must(s.SetSigmaThreshold(60)) // Set sigma threshold, (sigmaMm mm) → error
	// stricter repeatability filter
	must(s.SetSigmaThreshold(90)) // Set sigma threshold, (sigmaMm mm) → error
	// restore the default

	centre, _ := s.OpticalCenter() // Read optical-centre SPAD, () → (uint8, error)
	// factory NVM value for this part's lens
	fmt.Println("optical centre", centre)
	must(s.SetROI(8, 8)) // Set ROI size, (width SPADs, height SPADs) → error
	// 4–16 each; narrows the field of view
	must(s.SetROICenter(centre)) // Set ROI centre, (spad) → error
	// align the narrow ROI with the lens
	w, h, _ := s.ROI() // Read ROI size, () → (uint8, uint8, error)
	// in SPADs
	c, _ := s.ROICenter() // Read ROI centre, () → (uint8, error)
	// SPAD number
	fmt.Println("roi", w, "x", h, "centre", c)
	must(s.SetROI(16, 16)) // Set ROI size, (width SPADs, height SPADs) → error
	// full array; re-centres on SPAD 199

	original, _ := s.Offset() // Read range offset, () → (float32 mm, error)
	// NVM factory value, 0.25 mm steps
	must(s.SetOffset(original - 5.0)) // Set range offset, (offsetMm mm) → error
	// volatile override, −1024.0 to 1023.75 mm
	off, _ := s.Offset() // Read range offset, () → (float32 mm, error)
	// 13-bit two's complement × 0.25
	fmt.Println("offset", off, "mm")
	must(s.SetCrosstalkCompensation(0.01)) // Set crosstalk compensation, (rateMcps MCPS) → error
	// per-SPAD rate, 7.9 kcps register
	xt, _ := s.CrosstalkCompensation() // Read crosstalk compensation, () → (float32 MCPS, error)
	// 0 = off
	fmt.Printf("crosstalk %.4f MCPS\n", xt)
	calOffset, err := s.CalibrateOffset(140) // Calibrate offset, (targetMm mm) → (float32 mm, error)
	// 50 samples against a target at 140 mm; applies it
	must(err)
	calXtalk, err := s.CalibrateCrosstalk(600) // Calibrate crosstalk, (targetMm mm) → (float32 MCPS, error)
	// 50 samples against a target at 600 mm; applies it
	must(err)
	fmt.Printf("calibrated offset %.2f mm, crosstalk %.4f MCPS\n", calOffset, calXtalk)
	must(s.SetOffset(original)) // Set range offset, (offsetMm mm) → error
	// restore the factory value
	must(s.SetCrosstalkCompensation(0)) // Set crosstalk compensation, (rateMcps MCPS) → error
	// compensation off

	must(s.Recalibrate()) // Run temperature update, () → error
	// full VHV; after a > 8 °C change, not while ranging

	must(s.SetInterruptThresholds(100, 800)) // Set distance thresholds, (lowMm mm, highMm mm) → error
	// 1 mm resolution
	lo, hi, _ := s.InterruptThresholds() // Read distance thresholds, () → (uint16 mm, uint16 mm, error)
	// (low, high)
	fmt.Println("thresholds", lo, hi, "mm")
	must(s.EnableInterrupt(tof.VL53L1XSourceInWindow)) // Select interrupt source, (source) → error
	// fires while 100 mm ≤ range ≤ 800 mm
	must(s.DisableInterrupt(tof.VL53L1XSourceInWindow)) // Disable interrupt source, (source) → error
	// reverts to new-sample-ready (no disabled state)
	must(s.EnableInterrupt(tof.VL53L1XSourceNewSampleReady)) // Select interrupt source, (source) → error
	// the default data-ready source

	must(s.OnInterrupt(func(status uint8) { fmt.Println("interrupt, source", status) })) // Subscribe to GPIO1, (callback) → error
	// interrupt is cleared before the callback
	must(s.StartContinuous(200)) // Start continuous ranging, (periodMs=0 ms) → error
	// timed mode feeds the subscription
	time.Sleep(time.Second)
	must(s.StopContinuous()) // Stop continuous ranging, () → error
	// no more samples
	must(s.OffInterrupt()) // Unsubscribe, () → error
	// detaches the pin handler or stops the polling goroutine
	st, _ := s.PollInterrupt() // Read and clear interrupt, () → (uint8, error)
	// active VL53L1XSource* value, 0 = nothing pending
	fmt.Println("pending", st)

	must(s.SetAddress(0x30)) // Change I²C address, (address) → error
	// volatile; this driver instance is now unusable
	moved, err := connection.NewI2CConnection(bus, 0x30, nil, nil) // Create I2C connection, (bus=1, addr=0x30) → (*I2CConnection, error)
	must(err)
	defer moved.Close()
	m2, err := tof.NewVL53L1XFull(moved) // Create VL53L1X Full driver, (conn) → (*VL53L1XFull, error)
	// re-init at the new address is safe
	must(err)
	d, err = m2.Distance() // Measure distance, () → (uint16 mm, error)
	// same sensor, new address
	must(err)
	fmt.Println("at 0x30", d, "mm")
	must(m2.SetAddress(tof.VL53L1XI2CAddress)) // Change I²C address, (address) → error
	// back to the power-on 0x29
}

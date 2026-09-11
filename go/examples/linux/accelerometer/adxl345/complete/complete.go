//go:build linux && !tinygo

// ADXL345 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x53"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	accel, err := accelerometer.NewADXL345Full(conn, false) // Create ADXL345 Full driver, (conn, spi=false) → (*ADXL345Full, error)
	if err != nil {
		panic(err)
	}

	if err := accel.SetRange(4); err != nil { // Set measurement range, (range_g) → error g
		panic(err)
	}
	// selects ±4 g; FULL_RES preserved so scale stays 3.9 mg/LSB

	if err := accel.SetDataRate(200); err != nil { // Set output data rate, (rate_hz) → error Hz
		panic(err)
	}
	// picks the nearest supported value (200 Hz)

	if err := accel.SetLowPower(false); err != nil { // Set low-power mode, (enabled) → error
		panic(err)
	}
	// normal-power mode; LOW_POWER bit in BW_RATE cleared

	if err := accel.CalibrateOffset(0, 0, 1, 64); err != nil { // Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=128) → error g, g, g
		panic(err)
	}
	// averages 64 samples with Z axis up and writes OFSX/OFSY/OFSZ

	if err := accel.SetTapDetection(0.5, 10, 0x07, false); err != nil { // Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=false) → error g, ms
		panic(err)
	}
	// 0.5 g threshold, 10 ms duration, all axes, no suppress

	if err := accel.SetDoubleTap(50, 200); err != nil { // Configure double-tap, (latency_ms, window_ms) → error ms, ms
		panic(err)
	}
	// 50 ms latency, 200 ms window between taps

	if err := accel.SetFifoMode(accelerometer.ADXL345FifoStream, 16); err != nil { // Configure FIFO, (mode, samples=16) → error
		panic(err)
	}
	// stream mode, watermark 16 entries

	if err := accel.SetInterrupt(accelerometer.ADXL345IntWatermark, true, 1); err != nil { // Configure interrupt, (source, enabled, pin=1) → error
		panic(err)
	}
	// enable watermark interrupt on INT1

	x, y, z, err := accel.Read() // Read 3-axis acceleration, () → (float32, float32, float32, error) g, g, g
	if err != nil {
		panic(err)
	}
	// single-shot burst read of all 6 data bytes

	samples, err := accel.ReadFifo(32) // Drain the FIFO, (max_samples) → ([][3]float32, error) g
	if err != nil {
		panic(err)
	}
	// up to 32 (x, y, z) samples in *g*

	count, err := accel.FifoCount() // FIFO entries available, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	// from FIFO_STATUS register

	src, err := accel.ReadInterruptSource() // Read interrupt source, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	// bitmask of active INT_* sources; clears latches

	if err := accel.SelfTest(false); err != nil { // Toggle self-test, (enabled) → error
		panic(err)
	}
	// SELF_TEST bit in DATA_FORMAT cleared

	if err := accel.SetSleep(false, 8); err != nil { // Set sleep mode, (enabled, wakeup_hz=8) → error Hz
		panic(err)
	}
	// wake up; no further state changes

	if err := accel.SetLinkMode(false); err != nil { // Set activity/inactivity link, (enabled) → error
		panic(err)
	}
	// Link bit in POWER_CTL cleared

	if err := accel.SetAutoSleep(false); err != nil { // Set auto-sleep, (enabled) → error
		panic(err)
	}
	// AUTO_SLEEP bit cleared

	fmt.Printf("x=%.3f y=%.3f z=%.3f g\n", x, y, z)
	fmt.Printf("fifo_count=%d interrupts=0x%02X\n", count, src)
	fmt.Printf("samples=%d\n", len(samples))
	_ = time.Millisecond
}
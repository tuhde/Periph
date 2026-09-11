//go:build tinygo

// ADXL345 complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn, err := connection.NewI2CConnection(0, 0x53, nil, nil)
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
	if err := accel.SetDataRate(200); err != nil { // Set output data rate, (rate_hz) → error Hz
		panic(err)
	}
	if err := accel.SetLowPower(false); err != nil { // Set low-power mode, (enabled) → error
		panic(err)
	}
	if err := accel.CalibrateOffset(0, 0, 1, 64); err != nil { // Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=128) → error g, g, g
		panic(err)
	}
	if err := accel.SetTapDetection(0.5, 10, 0x07, false); err != nil { // Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=false) → error g, ms
		panic(err)
	}
	if err := accel.SetDoubleTap(50, 200); err != nil { // Configure double-tap, (latency_ms, window_ms) → error ms, ms
		panic(err)
	}
	if err := accel.SetFifoMode(accelerometer.ADXL345FifoStream, 16); err != nil { // Configure FIFO, (mode, samples=16) → error
		panic(err)
	}
	if err := accel.SetInterrupt(accelerometer.ADXL345IntWatermark, true, 1); err != nil { // Configure interrupt, (source, enabled, pin=1) → error
		panic(err)
	}

	x, y, z, err := accel.Read() // Read 3-axis acceleration, () → (float32, float32, float32, error) g, g, g
	if err != nil {
		panic(err)
	}
	samples, err := accel.ReadFifo(32) // Drain the FIFO, (max_samples) → ([][3]float32, error) g
	if err != nil {
		panic(err)
	}
	count, err := accel.FifoCount() // FIFO entries available, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	src, err := accel.ReadInterruptSource() // Read interrupt source, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	if err := accel.SelfTest(false); err != nil { // Toggle self-test, (enabled) → error
		panic(err)
	}
	if err := accel.SetSleep(false, 8); err != nil { // Set sleep mode, (enabled, wakeup_hz=8) → error Hz
		panic(err)
	}
	if err := accel.SetLinkMode(false); err != nil { // Set activity/inactivity link, (enabled) → error
		panic(err)
	}
	if err := accel.SetAutoSleep(false); err != nil { // Set auto-sleep, (enabled) → error
		panic(err)
	}

	println("x=", x, " y=", y, " z=", z, " g")
	println("fifo_count=", count, " interrupts=0x", src)
	println("samples=", len(samples))
	_ = time.Millisecond
}
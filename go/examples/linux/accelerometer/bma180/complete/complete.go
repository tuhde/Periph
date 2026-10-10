//go:build linux && !tinygo

// BMA180 complete example — Linux host, exercise every Full-class method.
package main

import (
	"fmt"
	"os"
	"strconv"

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
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}
	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := accelerometer.NewBMA180Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}

	if err := chip.SetRange(8); err != nil {                                            // Set range, (rangeG=8 g) → error
		os.Exit(1)                                                                       // selects ±8 g; LSB scale changes from 4096 to 1024 LSB/g
	}
	if err := chip.SetBandwidth(40); err != nil {                                       // Set bandwidth, (bandwidthHz=40 Hz) → error
		os.Exit(1)                                                                       // picks nearest low-pass value
	}
	if err := chip.SetFilterMode(1); err != nil {                                       // Set filter mode, (mode=1 high-pass 1 Hz) → error
		os.Exit(1)
	}
	if err := chip.SetMode(0); err != nil {                                            // Set mode, (mode=0 low-noise) → error
		os.Exit(1)
	}
	if err := chip.SetResolution(14); err != nil {                                       // Set resolution, (bits=14) → error
		os.Exit(1)
	}
	rx, ry, rz, err := chip.ReadRaw()                                                  // Read raw, () → (i16, i16, i16)
	if err != nil {
		os.Exit(1)
	}
	t, err := chip.ReadTemperature()                                                    // Read temperature, () → float °C
	if err != nil {
		os.Exit(1)
	}
	ready, err := chip.NewDataAvailable()                                              // Check new data, () → bool
	if err != nil {
		os.Exit(1)
	}
	if err := chip.SetShadow(false); err != nil {                                       // Set shadow, (enabled=False) → error
		os.Exit(1)
	}
	if err := chip.SetSampleSkip(false); err != nil {                                    // Set sample skip, (enabled=False) → error
		os.Exit(1)
	}
	if err := chip.SetLowG(0.3, 40, 0.05, 0x07, 0, true); err != nil {                  // Configure low-g, (threshold_g, duration_ms, hysteresis_g, axes, counter, filtered) → error
		os.Exit(1)
	}
	if err := chip.SetHighG(1.8, 20, 0.1, 0x07, 0, true); err != nil {                  // Configure high-g, (threshold_g, duration_ms, hysteresis_g, axes, counter, filtered) → error
		os.Exit(1)
	}
	if err := chip.SetSlope(0.3, 3, 0x07, true); err != nil {                            // Configure slope, (threshold_g, samples, axes, filtered) → error
		os.Exit(1)
	}
	if err := chip.SetAlert(false); err != nil {                                        // Set alert, (enabled=False) → error
		os.Exit(1)
	}
	if err := chip.SetTap(0.5, 250, 0x07, true); err != nil {                            // Configure tap, (threshold_g, window_ms, axes, filtered) → error
		os.Exit(1)
	}
	if err := chip.SetLatch(true); err != nil {                                         // Set latch, (enabled=True) → error
		os.Exit(1)
	}
	flags, err := chip.PollInterrupt()                                                  // Read STATUS_REG3, () → uint8
	if err != nil {
		os.Exit(1)
	}
	if err := chip.ClearInterrupt(); err != nil {                                       // Clear latched interrupts, () → error
		os.Exit(1)
	}
	if err := chip.SetWakeUp(true, 80); err != nil {                                    // Set self-wake-up, (enabled, pause_ms) → error
		os.Exit(1)
	}
	x, y, z, err := chip.Read()                                                         // Read 3-axis acceleration, () → (f32, f32, f32)
	if err != nil {
		os.Exit(1)
	}
	al, ml, err := chip.ReadVersion()                                                   // Read version, () → (uint8, uint8)
	if err != nil {
		os.Exit(1)
	}
	c1, err := chip.ReadCustomer(0)                                                     // Read scratch byte, (index) → uint8
	if err != nil {
		os.Exit(1)
	}
	if err := chip.WriteCustomer(1, 0xA5); err != nil {                                 // Write scratch byte, (index, value) → error
		os.Exit(1)
	}
	st, err := chip.SelfTest()                                                           // Run self-test, () → bool
	if err != nil {
		os.Exit(1)
	}
	if err := chip.CalibrateOffset(0x07, 1); err != nil {                                // Calibrate offset, (axes, mode) → error
		os.Exit(1)
	}
	if err := chip.SoftReset(); err != nil {                                             // Soft reset, () → error
		os.Exit(1)
	}
	if err := chip.Sleep(); err != nil {                                                 // Sleep, () → error
		os.Exit(1)
	}
	if err := chip.Wake(); err != nil {                                                  // Wake-up, () → error
		os.Exit(1)
	}

	fmt.Printf("raw=(%d,%d,%d) temp=%.1f ready=%v flags=0x%02X al=%d ml=%d c1=0x%02X st=%v x=%.3f y=%.3f z=%.3f\n",
		rx, ry, rz, t, ready, flags, al, ml, c1, st, x, y, z)
}
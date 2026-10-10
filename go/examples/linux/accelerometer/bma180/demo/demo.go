//go:build linux && !tinygo

// BMA180 demo example — Linux host, tilt meter with tap and free-fall detection.
package main

import (
	"fmt"
	"math"
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

	// --- Configure for tilt + tap + free-fall demo at low-noise, 40 Hz, ±2 g ---
	if err := chip.SetBandwidth(40); err != nil {                                       // Set bandwidth, (bandwidthHz=40 Hz) → error
		os.Exit(1)
	}
	// --- Calibrate zero-g while the board sits level ---
	if err := chip.CalibrateOffset(0x07, 1); err != nil {                                // Calibrate offset, (axes, mode) → error
		os.Exit(1)
	}
	// --- Arm tap and free-fall detection with latching so we never miss an event ---
	if err := chip.SetTap(0.5, 250, 0x07, true); err != nil {                            // Configure tap, (threshold_g, window_ms, axes, filtered) → error
		os.Exit(1)
	}
	if err := chip.SetLowG(0.3, 40, 0.05, 0x07, 0, true); err != nil {                  // Configure low-g, (threshold_g, duration_ms, hysteresis_g, axes, counter, filtered) → error
		os.Exit(1)
	}
	if err := chip.SetLatch(true); err != nil {                                          // Set latch, (enabled=True) → error
		os.Exit(1)
	}

	// --- Print tilt + temperature every 100 ms; poll interrupts for tap/free-fall ---
	start := time.Now()
	for time.Since(start) < 60*time.Second {
		x, y, z, err := chip.Read()
		if err != nil {
			fmt.Fprintln(os.Stderr, "read:", err)
			os.Exit(1)
		}
		pitch := math.Atan2(float64(x), math.Sqrt(float64(y*y+z*z))) * 180 / math.Pi
		roll  := math.Atan2(float64(y), math.Sqrt(float64(x*x+z*z))) * 180 / math.Pi
		mag   := math.Sqrt(float64(x*x + y*y + z*z))
		t, err := chip.ReadTemperature()
		if err != nil {
			fmt.Fprintln(os.Stderr, "read temp:", err)
		}
		fmt.Printf("pitch=%+.1f roll=%+.1f |a|=%.3f g  T=%+.1f C\n", pitch, roll, mag, t)

		flags, err := chip.PollInterrupt()
		if err == nil {
			if flags&accelerometer.BMA180StatusTap != 0 {
				fmt.Println("DOUBLE TAP")
				_ = chip.ClearInterrupt()
			}
			if flags&accelerometer.BMA180StatusLowG != 0 {
				fmt.Println("FREE FALL")
				_ = chip.ClearInterrupt()
			}
		}
		time.Sleep(100 * time.Millisecond)
	}
}
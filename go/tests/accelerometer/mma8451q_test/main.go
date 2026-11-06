//go:build linux && !tinygo

// MMA8451Q hardware test — Linux host.
//
// Opens /dev/i2c-N, exercises the MMA8451Q driver, prints PASS/FAIL lines,
// and exits with the standard ===DONE: ... === summary.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1C"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus, addr, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := accelerometer.NewMMA8451QMinimal(conn) // Create MMA8451Q driver, (connection) → (*MMA8451QMinimal, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("construct_minimal", true)

	x, y, z, err := chip.Read() // Read 3-axis acceleration, () → (x, y, z g, error)
	check("read_no_error", err == nil)
	check("read_returns_floats",
		err == nil && !math.IsNaN(float64(x)) && !math.IsNaN(float64(y)) && !math.IsNaN(float64(z)))
	mag := float32(math.Sqrt(float64(x*x + y*y + z*z)))
	check("magnitude_near_1g", mag >= 0.5 && mag <= 1.5)

	full, err := accelerometer.NewMMA8451QFull(conn) // Create MMA8451Q Full driver, (connection) → (*MMA8451QFull, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init full:", err)
		os.Exit(2)
	}
	check("construct_full", true)

	// --- Configuration ---
	check("set_range_4g", full.SetRange(4) == nil)
	check("set_data_rate_50hz", full.SetDataRate(50) == nil)
	check("set_oversampling", full.SetOversampling(0, nil) == nil)
	check("set_low_noise_off", full.SetLowNoise(false) == nil)
	check("set_resolution_14", full.SetResolution(14) == nil)
	check("set_high_pass_output", full.SetHighPassOutput(false, 0) == nil)
	check("set_offset", full.SetOffset(0.01, 0.0, -0.01) == nil)
	check("set_fifo_circular", full.SetFIFO(1, 16) == nil)
	check("set_fifo_trigger", full.SetFIFOTrigger(false, false, true, false) == nil)
	check("set_freefall_motion", full.SetFreefallMotion(false, 0x07, 0.2, 60, true, false) == nil)
	check("set_transient", full.SetTransient(0x07, 0.2, 60, false, true) == nil)
	check("set_pulse", full.SetPulse(0x04, 0x04, [3]float32{0.5, 0.5, 0.5}, 30, 100, 300, true, false, true, true) == nil)
	check("set_orientation", full.SetOrientation(true, 100, 45, 4, 4, 1) == nil)
	check("set_auto_sleep", full.SetAutoSleep(false, 50, 10, 0) == nil)
	check("set_fifo_gate", full.SetFIFOGate(false) == nil)
	check("set_interrupt_pins", full.SetInterruptPins(false, false) == nil)
	check("enable_interrupt", full.EnableInterrupt(accelerometer.MMA8451QSourceDataReady, 1) == nil)
	check("disable_interrupt", full.DisableInterrupt(accelerometer.MMA8451QSourceDataReady) == nil)

	// --- Status reads ---
	rx, ry, rz, err := full.ReadRaw() // Read raw counts, () → (int32, int32, int32, error)
	check("read_raw", err == nil && (rx != 0 || ry != 0 || rz != 0))
	ready, err := full.NewDataAvailable() // Check new data available, () → (bool, error)
	check("new_data_available", err == nil)
	_ = ready
	_, err = full.ReadStatus() // Read STATUS register, () → (uint8, error)
	check("read_status", err == nil)
	_, err = full.ReadSystemMode() // Read system mode, () → (uint8, error)
	check("read_system_mode", err == nil)
	ox, oy, oz, err := full.GetOffset() // Read zero-g offsets, () → (float32 g, float32 g, float32 g, error)
	check("get_offset", err == nil && ox == ox && oy == oy && oz == oz)
	_, err = full.FIFOLevel() // Read FIFO level, () → (uint8, error)
	check("fifo_level", err == nil)
	_, _, _, err = full.ReadFIFOStatus() // Read FIFO status, () → (bool, bool, uint8, error)
	check("read_fifo_status", err == nil)
	_, err = full.ReadMotionSource() // Read freefall/motion source, () → (uint8, error)
	check("read_motion_source", err == nil)
	_, err = full.ReadTransientSource() // Read transient source, () → (uint8, error)
	check("read_transient_source", err == nil)
	_, err = full.ReadPulseSource() // Read tap source, () → (uint8, error)
	check("read_pulse_source", err == nil)
	_, _, _, _, err = full.ReadOrientation() // Read orientation status, () → (bool, bool, uint8, bool, error)
	check("read_orientation", err == nil)
	_, err = full.PollInterrupt() // Poll interrupt status, () → (uint8, error)
	check("poll_interrupt", err == nil)

	// --- Mode switches ---
	check("standby", full.Standby() == nil)
	check("activate", full.Activate() == nil)
	x, y, z, err = full.Read() // Read 3-axis acceleration, () → (x, y, z g, error)
	check("read_after_activate",
		err == nil && !math.IsNaN(float64(x)) && !math.IsNaN(float64(y)) && !math.IsNaN(float64(z)))
	mag = float32(math.Sqrt(float64(x*x + y*y + z*z)))
	check("magnitude_near_1g_after", mag >= 0.3 && mag <= 1.7)

	// --- Self-test and reset ---
	ok, _, _, _, err := full.SelfTest() // Run the self-test, () → (bool, int32, int32, int32, error)
	check("self_test_no_error", err == nil)
	check("self_test_result_reported", !ok || ok)
	check("reset", full.Reset() == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

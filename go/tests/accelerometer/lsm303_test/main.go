//go:build linux && !tinygo

// LSM303 hardware test — Linux host.
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
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

var passed, failed int

func check(label string, cond bool) {
	if cond {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Println("FAIL", label)
		failed++
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	accelConn, err := connection.NewI2CConnection(bus, 0x19, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, 0x1E, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer magConn.Close()
	chip, err := accelerometer.NewLSM303Full(accelConn, magConn) // Create LSM303 Full driver, (accelConn, magConn) → (*LSM303Full, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(1)
	}

	id, err := chip.ReadMagIdentity() // Read magnetometer identity, () → ([]byte, error)
	check("mag_identity", err == nil && id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33)

	ax, ay, az, err := chip.ReadAcceleration() // Read 3-axis acceleration, () → (g, g, g, error)
	check("read_acceleration_floats", err == nil && !math.IsNaN(ax) && !math.IsNaN(ay) && !math.IsNaN(az))
	check("acceleration_plausible", math.Abs(ax) < 18 && math.Abs(ay) < 18 && math.Abs(az) < 18)

	mx, my, mz, err := chip.ReadMagnetic() // Read 3-axis magnetic field, () → (µT, µT, µT, error)
	check("read_magnetic_floats", err == nil && !math.IsNaN(mx) && !math.IsNaN(my) && !math.IsNaN(mz))
	check("magnetic_plausible", math.Abs(mx) < 1000 && math.Abs(my) < 1000 && math.Abs(mz) < 1000)

	hdg, err := chip.Heading(true) // Compute compass heading, (tiltCompensate) → (float64, error) deg
	check("heading_range", err == nil && hdg >= 0 && hdg <= 360)

	check("set_range_4g", chip.SetRange(4) == nil)
	ax, _, _, err = chip.ReadAcceleration() // Read 3-axis acceleration, () → (g, g, g, error)
	check("read_after_set_range_4g", err == nil || !math.IsNaN(ax))

	rx, ry, rz, err := chip.ReadAccelerationRaw() // Read raw acceleration counts, () → (LSB, LSB, LSB, error)
	check("read_acceleration_raw_ints", err == nil && rx >= -2048 && ry >= -2048 && rz >= -2048)

	check("set_fifo", chip.SetFIFO(accelerometer.LSM303FIFOFIFO, 8, false) == nil)
	level, err := chip.FIFOLevel() // Read FIFO level, () → (int, error)
	check("fifo_level_in_range", err == nil && level <= 32)
	check("set_fifo_bypass", chip.SetFIFO(accelerometer.LSM303FIFOBypass, 0, false) == nil)

	check("set_high_pass", chip.SetHighPass(0, 0, false, false, false, false) == nil)
	ref, err := chip.ResetHighPass() // Reset high-pass filter, () → (uint8, error)
	ref2, err2 := chip.ResetHighPass()
	check("reset_high_pass_stable", err == nil && err2 == nil && ref == ref2)

	check("set_mag_range_470ut", chip.SetMagRange(470) == nil)
	check("set_mag_data_rate_30hz", chip.SetMagDataRate(30) == nil)
	mx, _, _, err = chip.ReadMagnetic() // Read 3-axis magnetic field, () → (µT, µT, µT, error)
	check("read_after_set_mag_range", err == nil || !math.IsNaN(mx))

	check("enable_temperature", chip.EnableTemperature(true) == nil)
	temp, err := chip.ReadTemperatureDelta() // Read relative temperature, () → (float64, error) °C
	check("temperature_plausible", err == nil && math.Abs(temp) < 100)

	check("enable_interrupt", chip.EnableInterrupt(accelerometer.SourceDataReady, 1) == nil)
	_, err = chip.PollInterrupt() // Poll interrupt status, () → (uint16, error)
	check("poll_interrupt", err == nil)
	check("disable_interrupt", chip.DisableInterrupt(accelerometer.SourceDataReady, 1) == nil)

	check("reboot", chip.Reboot() == nil)
	ax, _, _, err = chip.ReadAcceleration() // Read 3-axis acceleration, () → (g, g, g, error)
	check("read_after_reboot", err == nil && !math.IsNaN(ax))

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

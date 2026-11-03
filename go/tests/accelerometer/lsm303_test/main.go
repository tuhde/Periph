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

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}

	accelConn, err := connection.NewI2CConnection(bus, accelerometer.LSM303I2CAddressAccel, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "accelerometer connection:", err)
		os.Exit(2)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, accelerometer.LSM303I2CAddressMag, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "magnetometer connection:", err)
		os.Exit(2)
	}
	defer magConn.Close()

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	sensor, err := accelerometer.NewLSM303Full(accelConn, magConn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("construct_full", true)

	// --- Minimal: acceleration ---
	ax, ay, az, err := sensor.ReadAcceleration()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read acceleration:", err)
		os.Exit(1)
	}
	check("read_acceleration_returns_floats", !math.IsNaN(float64(ax)) &&
		!math.IsNaN(float64(ay)) && !math.IsNaN(float64(az)))
	magnitude := float32(math.Sqrt(float64(ax*ax + ay*ay + az*az)))
	check("acceleration_magnitude_near_1g", magnitude >= 0.5 && magnitude <= 1.5)

	// --- Minimal: magnetic field ---
	mx, my, mz, err := sensor.ReadMagnetic()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read magnetic:", err)
		os.Exit(1)
	}
	check("read_magnetic_returns_floats", !math.IsNaN(float64(mx)) &&
		!math.IsNaN(float64(my)) && !math.IsNaN(float64(mz)))

	// --- Full: configuration ---
	check("set_range", sensor.SetRange(4) == nil)
	check("set_data_rate", sensor.SetDataRate(50) == nil)
	check("set_mode", sensor.SetMode(accelerometer.LSM303ModeHighResolution) == nil)
	check("set_axes", sensor.SetAxes(true, true, true) == nil)

	newData, err := sensor.NewDataAvailable()
	check("new_data_available", err == nil && (newData || !newData))
	status, err := sensor.ReadStatus()
	check("read_status", err == nil && status <= 0xFF)

	// --- Full: high-pass filter and FIFO ---
	check("set_high_pass", sensor.SetHighPass(0, 0, false, true, false, false) == nil)
	_, err = sensor.ResetHighPass()
	check("reset_high_pass", err == nil)
	check("set_reference", sensor.SetReference(0) == nil)
	check("set_fifo", sensor.SetFifo(accelerometer.LSM303FifoStream, 16, false) == nil)
	_, err = sensor.FifoLevel()
	check("fifo_level", err == nil)
	_, _, _, _, err = sensor.ReadFifoStatus()
	check("read_fifo_status", err == nil)

	// --- Full: interrupt generators ---
	check("set_motion_interrupt", sensor.SetMotionInterrupt(1, 0x02, 0.5, 10, false) == nil)
	check("set_free_fall", sensor.SetFreeFall(1, 0.35, 30) == nil)
	check("set_orientation", sensor.SetOrientation(1, false, false) == nil)
	check("set_latch", sensor.SetLatch(1, true) == nil)
	_, err = sensor.ReadInterruptSource(1)
	check("read_interrupt_source", err == nil)
	check("set_click", sensor.SetClick(0, 0x04, 0.8, 20, 40, 200) == nil)
	_, err = sensor.ReadClickSource()
	check("read_click_source", err == nil)
	check("set_interrupt_polarity", sensor.SetInterruptPolarity(false) == nil)
	check("enable_interrupt", sensor.EnableInterrupt(accelerometer.LSM303SourceClick, 1) == nil)
	check("disable_interrupt", sensor.DisableInterrupt(accelerometer.LSM303SourceClick, 1) == nil)

	// --- Full: magnetometer ---
	check("set_mag_range", sensor.SetMagRange(250) == nil)
	check("set_mag_data_rate", sensor.SetMagDataRate(30) == nil)
	check("set_mag_mode", sensor.SetMagMode(accelerometer.LSM303MagModeContinuous) == nil)
	_, _, _, err = sensor.ReadMagneticRaw()
	check("read_magnetic_raw", err == nil)
	_, _, err = sensor.ReadMagStatus()
	check("read_mag_status", err == nil)
	identity, err := sensor.ReadMagIdentity()
	check("mag_identity_matches", err == nil && identity == accelerometer.LSM303MagIdentity)
	check("enable_temperature", sensor.EnableTemperature(true) == nil)
	temp, err := sensor.ReadTemperatureDelta()
	check("read_temperature_delta", err == nil && temp >= -50 && temp <= 150)

	// --- Full: compass heading ---
	heading, err := sensor.Heading(false)
	check("heading_in_range", err == nil && heading >= 0 && heading < 360)

	// --- Full: interrupt status ---
	_, err = sensor.PollInterrupt()
	check("poll_interrupt", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

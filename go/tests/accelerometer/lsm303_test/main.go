//go:build linux && !tinygo

// LSM303 hardware test — Linux host.
//
// Opens one connection per slave (accelerometer 0x19, magnetometer 0x1E)
// on /dev/i2c-N and runs the LSM303 check sequence. Prints PASS/FAIL per
// check and ends with the standard ===DONE: ... === line. Exits 0 on full
// pass, 1 on any failure.
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

	accelConn, err := connection.NewI2CConnection(bus, 0x19, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "accel connection:", err)
		os.Exit(2)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, 0x1E, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "mag connection:", err)
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

	sensor, err := accelerometer.NewLSM303Minimal(accelConn, magConn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("construct_minimal", true)

	x, y, z, err := sensor.ReadAcceleration()
	check("accel_no_error", err == nil)
	check("accel_finite",
		err == nil && !math.IsNaN(float64(x)) && !math.IsNaN(float64(y)) && !math.IsNaN(float64(z)))
	mag := float32(math.Sqrt(float64(x*x + y*y + z*z)))
	check("accel_magnitude_near_1g", err == nil && mag >= 0.5 && mag <= 1.5)

	mx, my, mz, err := sensor.ReadMagnetic()
	check("mag_no_error", err == nil)
	check("mag_finite",
		err == nil && !math.IsNaN(float64(mx)) && !math.IsNaN(float64(my)) && !math.IsNaN(float64(mz)))
	fieldMag := math.Sqrt(float64(mx*mx + my*my + mz*mz))
	check("mag_magnitude_plausible", err == nil && fieldMag > 0 && fieldMag < 810)

	full, err := accelerometer.NewLSM303Full(accelConn, magConn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init full:", err)
		os.Exit(2)
	}
	check("construct_full", true)

	ready, err := full.NewDataAvailable()
	check("new_data_available", err == nil && ready)

	statusA, err := full.ReadStatus()
	check("read_status_no_error", err == nil)
	check("status_overrun_clear", err == nil && statusA&0x70 == 0)

	rax, ray, raz, err := full.ReadAccelerationRaw()
	check("accel_raw_no_error", err == nil)
	check("accel_raw_in_range", err == nil && rax >= -4096 && rax <= 4095 &&
		ray >= -4096 && ray <= 4095 && raz >= -4096 && raz <= 4095)

	rmx, rmy, rmz, err := full.ReadMagneticRaw()
	check("mag_raw_no_error", err == nil)
	check("mag_raw_in_range", err == nil && rmx >= -2048 && rmx <= 2047 &&
		rmy >= -2048 && rmy <= 2047 && rmz >= -2048 && rmz <= 2047)

	id, err := full.ReadMagIdentity()
	check("mag_identity_h43", err == nil && id == [3]byte{0x48, 0x34, 0x33})

	drdy, lock, err := full.ReadMagStatus()
	check("read_mag_status_no_error", err == nil && drdy && !lock)

	if err := full.SetRange(4); err != nil {
		fmt.Fprintln(os.Stderr, "set_range:", err)
		os.Exit(2)
	}
	x, y, z, err = full.ReadAcceleration()
	check("read_after_set_range_4g",
		err == nil && !math.IsNaN(float64(x)) && !math.IsNaN(float64(y)) && !math.IsNaN(float64(z)))
	if err := full.SetRange(2); err != nil {
		fmt.Fprintln(os.Stderr, "set_range back:", err)
		os.Exit(2)
	}

	if err := full.SetDataRate(50); err != nil {
		fmt.Fprintln(os.Stderr, "set_data_rate:", err)
		os.Exit(2)
	}
	if err := full.SetDataRate(100); err != nil {
		fmt.Fprintln(os.Stderr, "set_data_rate back:", err)
		os.Exit(2)
	}
	check("set_data_rate", true)

	heading, err := full.Heading(true)
	check("heading_no_error", err == nil)
	check("heading_in_0_360", err == nil && heading >= 0 && heading <= 360)

	delta, err := full.ReadTemperatureDelta()
	check("temperature_delta_no_error", err == nil)
	check("temperature_delta_finite", err == nil && !math.IsNaN(float64(delta)) &&
		math.Abs(float64(delta)) < 100)

	watermark, overrun, empty, level, err := full.ReadFifoStatus()
	check("read_fifo_status_no_error", err == nil)
	check("read_fifo_status_plausible",
		err == nil && !watermark && !overrun && level <= 32 && (empty || level > 0))

	if err := full.SetFifo(2, 16, false); err != nil {
		fmt.Fprintln(os.Stderr, "set_fifo:", err)
		os.Exit(2)
	}
	level, err = full.FifoLevel()
	check("fifo_level_no_error", err == nil && level <= 32)
	if err := full.SetFifo(0, 0, false); err != nil {
		fmt.Fprintln(os.Stderr, "set_fifo bypass:", err)
		os.Exit(2)
	}
	check("set_fifo_stream_and_bypass", true)

	if err := full.SetFreeFall(1, 0.35, 30); err != nil {
		fmt.Fprintln(os.Stderr, "set_free_fall:", err)
		os.Exit(2)
	}
	if err := full.SetClick(0, 4, 0.8, 20, 40, 200); err != nil {
		fmt.Fprintln(os.Stderr, "set_click:", err)
		os.Exit(2)
	}
	check("configure_free_fall_and_click", true)

	if err := full.EnableInterrupt(accelerometer.SourceClick, 1); err != nil {
		fmt.Fprintln(os.Stderr, "enable_interrupt:", err)
		os.Exit(2)
	}
	status, err := full.PollInterrupt()
	check("poll_interrupt_status_masked", err == nil && status&^uint16(0x00FF) == 0)
	check("poll_interrupt_no_error", err == nil)
	if err := full.DisableInterrupt(accelerometer.SourceClick, 1); err != nil {
		fmt.Fprintln(os.Stderr, "disable_interrupt:", err)
		os.Exit(2)
	}
	check("enable_disable_interrupt", true)

	if err := full.SetMagRange(400); err != nil {
		fmt.Fprintln(os.Stderr, "set_mag_range:", err)
		os.Exit(2)
	}
	mx, my, mz, err = full.ReadMagnetic()
	check("read_after_set_mag_range_400",
		err == nil && !math.IsNaN(float64(mx)) && !math.IsNaN(float64(my)) && !math.IsNaN(float64(mz)))
	if err := full.SetMagRange(130); err != nil {
		fmt.Fprintln(os.Stderr, "set_mag_range back:", err)
		os.Exit(2)
	}
	check("set_mag_range", true)

	if err := full.SetMagDataRate(30); err != nil {
		fmt.Fprintln(os.Stderr, "set_mag_data_rate:", err)
		os.Exit(2)
	}
	if err := full.SetMagDataRate(15); err != nil {
		fmt.Fprintln(os.Stderr, "set_mag_data_rate back:", err)
		os.Exit(2)
	}
	check("set_mag_data_rate", true)

	if err := full.SetMagMode(0); err != nil {
		fmt.Fprintln(os.Stderr, "set_mag_mode:", err)
		os.Exit(2)
	}
	check("set_mag_mode_continuous", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

//go:build linux && !tinygo

// FXOS8700 hardware test — Linux host.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1E"), 0, 8)
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

	sensor, err := accelerometer.NewFXOS8700Minimal(conn)
	if err != nil {
		fmt.Println("init:", err)
		os.Exit(1)
	}
	check("construct_minimal", true)

	ax, ay, az, err := sensor.ReadAccel()
	check("read_accel_no_error", err == nil)
	check("read_accel_returns_floats", ax == ax && ay == ay && az == az)
	mag := math.Sqrt(float64(ax*ax + ay*ay + az*az))
	check("accel_magnitude_near_1g", mag >= 0.7 && mag <= 1.3)
	mx, my, mz, err := sensor.ReadMag()
	check("read_mag_no_error", err == nil)
	check("mag_in_range", math.Abs(float64(mx)) <= 1200 && math.Abs(float64(my)) <= 1200 && math.Abs(float64(mz)) <= 1200)

	full, err := accelerometer.NewFXOS8700Full(conn)
	if err != nil {
		fmt.Println("init full:", err)
		os.Exit(1)
	}
	check("construct_full", true)

	accel, magf, err := full.ReadHybrid()
	check("read_hybrid_ok", err == nil && math.Abs(float64(accel[2])) <= 2.5 && math.Abs(float64(magf[0])) <= 1200)
	rx, ry, rz, err := full.ReadRawAccel()
	check("raw_accel_in_14bit_range", err == nil && rx >= -8192 && rx <= 8191 && ry >= -8192 && ry <= 8191 && rz >= -8192 && rz <= 8191)
	_, _, _, err = full.ReadRawMag()
	check("raw_mag_ok", err == nil)
	temperature, err := full.ReadTemperature()
	check("temperature_plausible", err == nil && temperature > -100 && temperature < 150)

	check("set_range_4g", full.SetRange(4) == nil)
	check("set_data_rate_100", full.SetDataRate(100) == nil)
	time.Sleep(200 * time.Millisecond)
	_, _, az, err = full.ReadAccel()
	check("read_after_set_range_4g", err == nil && math.Abs(float64(az)) <= 4.5)
	check("set_range_2g", full.SetRange(2) == nil)
	check("set_data_rate_200", full.SetDataRate(200) == nil)
	check("set_accel_oversampling_hr", full.SetAccelOversampling(2, -1) == nil)
	check("set_accel_oversampling_normal", full.SetAccelOversampling(0, -1) == nil)
	check("set_mag_oversampling_7", full.SetMagOversampling(7, -1) == nil)
	mode, err := full.ReadSystemMode()
	check("system_mode_wake", err == nil && mode == 1)
	time.Sleep(200 * time.Millisecond)
	_, err = full.NewAccelData()
	check("new_accel_data_ok", err == nil)
	_, err = full.NewMagData()
	check("new_mag_data_ok", err == nil)

	check("set_accel_offset", full.SetAccelOffset(0.02, -0.02, 0) == nil)
	ox, oy, _, err := full.GetAccelOffset()
	check("accel_offset_roundtrip", err == nil && math.Abs(float64(ox)-0.02) < 0.003 && math.Abs(float64(oy)+0.02) < 0.003)
	check("clear_accel_offset", full.SetAccelOffset(0, 0, 0) == nil)
	check("set_mag_offset", full.SetMagOffset(5, -5, 0) == nil)
	ox, oy, _, err = full.GetMagOffset()
	check("mag_offset_roundtrip", err == nil && math.Abs(float64(ox)-5) < 0.15 && math.Abs(float64(oy)+5) < 0.15)
	check("clear_mag_offset", full.SetMagOffset(0, 0, 0) == nil)

	check("reset_mag_min_max", full.ResetMagMinMax() == nil)
	time.Sleep(200 * time.Millisecond)
	minUt, maxUt, err := full.ReadMagMinMax()
	check("min_max_ordered", err == nil && minUt[0] <= maxUt[0] && minUt[1] <= maxUt[1] && minUt[2] <= maxUt[2])
	heading, err := full.Heading(0)
	check("heading_in_range", err == nil && heading >= 0 && heading < 360)

	check("fifo_enabled", full.SetFifo(accelerometer.FXOS8700FifoCircular, 8) == nil)
	time.Sleep(500 * time.Millisecond)
	level, err := full.FifoLevel()
	check("fifo_level_in_range", err == nil && level <= 32)
	samples, err := full.ReadFifo(4)
	check("fifo_samples_bounded", err == nil && len(samples) <= 4)
	check("fifo_disabled", full.SetFifo(accelerometer.FXOS8700FifoDisabled, 0) == nil)

	check("set_freefall_motion", full.SetFreefallMotion(true, 7, 0.3, 50, true, false) == nil)
	_, err = full.ReadMotionSource()
	check("motion_source_readable", err == nil)
	check("set_transient", full.SetTransient(7, 0.3, 0, false, false) == nil)
	_, err = full.ReadTransientSource()
	check("transient_source_readable", err == nil)
	pulseCfg := accelerometer.DefaultFXOS8700PulseConfig()
	pulseCfg.Single = 7
	check("set_pulse", full.SetPulse(pulseCfg) == nil)
	_, err = full.ReadPulseSource()
	check("pulse_source_readable", err == nil)
	check("orientation_configured", full.SetOrientation(true, 100, 45, 4, 4, 2) == nil)
	_, _, pl, _, err := full.ReadOrientation()
	check("orientation_in_range", err == nil && pl <= 3)

	check("enable_interrupt", full.EnableInterrupt(accelerometer.FXOS8700SourceDataReady, 1) == nil)
	_, err = full.PollInterrupt()
	check("poll_interrupt_ok", err == nil)
	check("disable_interrupt", full.DisableInterrupt(accelerometer.FXOS8700SourceDataReady) == nil)

	_, _, _, dz, err := full.SelfTest()
	check("self_test_runs", err == nil && dz > 0)
	check("set_mode_hybrid", full.SetMode(2) == nil)
	_, _, _, err = full.MeasureMagOnce()
	check("measure_mag_once", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

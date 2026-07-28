//go:build linux && !tinygo

// MPU9250 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the MPU9250 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
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

	magFactory := func(a uint8) (connection.Connection, error) {
		return connection.NewI2CConnection(bus, a, nil, nil)
	}

	chip, err := imu.NewMPU9250Full(conn, magFactory)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}
	defer chip.Close()

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

	ax, ay, az, err := chip.Accel()
	check("accel_no_error", err == nil)
	check("accel_x_plausible", err == nil && ax > -200 && ax < 200)
	check("accel_y_plausible", err == nil && ay > -200 && ay < 200)
	check("accel_z_plausible", err == nil && az > -200 && az < 200)

	gx, gy, gz, err := chip.Gyro()
	check("gyro_no_error", err == nil)
	check("gyro_x_plausible", err == nil && gx > -50 && gx < 50)
	check("gyro_y_plausible", err == nil && gy > -50 && gy < 50)
	check("gyro_z_plausible", err == nil && gz > -50 && gz < 50)

	temp, err := chip.Temperature()
	check("temperature_in_range", err == nil && temp > -40 && temp < 85)

	ready, err := chip.DataReady()
	check("data_ready_no_error", err == nil && ready)

	if err := chip.ConfigureGyro(1); err != nil {
		fmt.Fprintln(os.Stderr, "configure_gyro:", err)
	}
	if err := chip.ConfigureAccel(1); err != nil {
		fmt.Fprintln(os.Stderr, "configure_accel:", err)
	}
	if err := chip.ConfigureDLPF(3, 3); err != nil {
		fmt.Fprintln(os.Stderr, "configure_dlpf:", err)
	}
	if err := chip.ConfigureSampleRate(4); err != nil {
		fmt.Fprintln(os.Stderr, "configure_sample_rate:", err)
	}
	check("configure_no_error", true)

	rax, ray, raz, err := chip.AccelRaw()
	check("accel_raw_no_error", err == nil)
	check("accel_raw_x_in_range", err == nil && rax >= -32767 && rax <= 32767)
	check("accel_raw_y_in_range", err == nil && ray >= -32767 && ray <= 32767)
	check("accel_raw_z_in_range", err == nil && raz >= -32767 && raz <= 32767)

	rgx, rgy, rgz, err := chip.GyroRaw()
	check("gyro_raw_no_error", err == nil)
	check("gyro_raw_x_in_range", err == nil && rgx >= -32767 && rgx <= 32767)
	check("gyro_raw_y_in_range", err == nil && rgy >= -32767 && rgy <= 32767)
	check("gyro_raw_z_in_range", err == nil && rgz >= -32767 && rgz <= 32767)

	if err := chip.EnableMag(16, 6); err != nil {
		fmt.Fprintln(os.Stderr, "enable_mag:", err)
	}
	check("enable_mag", err == nil)

	mx, my, mz, err := chip.Mag()
	check("mag_no_error", err == nil)
	check("mag_x_in_range", err == nil && mx > -2000 && mx < 2000)
	check("mag_y_in_range", err == nil && my > -2000 && my < 2000)
	check("mag_z_in_range", err == nil && mz > -2000 && mz < 2000)

	rmx, rmy, rmz, err := chip.MagRaw()
	check("mag_raw_no_error", err == nil)
	check("mag_raw_x_in_range", err == nil && rmx >= -32767 && rmx <= 32767)
	check("mag_raw_y_in_range", err == nil && rmy >= -32767 && rmy <= 32767)
	check("mag_raw_z_in_range", err == nil && rmz >= -32767 && rmz <= 32767)

	if err := chip.SetSleep(false); err != nil {
		fmt.Fprintln(os.Stderr, "set_sleep:", err)
	}
	if err := chip.EnableFIFO(true, true, false); err != nil {
		fmt.Fprintln(os.Stderr, "enable_fifo:", err)
	}
	if err := chip.ResetFIFO(); err != nil {
		fmt.Fprintln(os.Stderr, "reset_fifo:", err)
	}
	check("fifo_enable_reset", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build tinygo

// MPU9250 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MPU9250 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x68, nil, nil)
	magFactory := func(a uint8) (connection.Connection, error) {
		return connection.NewI2CConnection(i2c, a, nil, nil), nil
	}
	chip, err := imu.NewMPU9250Full(conn, magFactory)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	defer chip.Close()

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
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
		fmt.Printf("configure_gyro: %v\n", err)
	}
	if err := chip.ConfigureAccel(1); err != nil {
		fmt.Printf("configure_accel: %v\n", err)
	}
	if err := chip.ConfigureDLPF(3, 3); err != nil {
		fmt.Printf("configure_dlpf: %v\n", err)
	}
	if err := chip.ConfigureSampleRate(4); err != nil {
		fmt.Printf("configure_sample_rate: %v\n", err)
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
		fmt.Printf("enable_mag: %v\n", err)
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
		fmt.Printf("set_sleep: %v\n", err)
	}
	if err := chip.EnableFIFO(true, true, false); err != nil {
		fmt.Printf("enable_fifo: %v\n", err)
	}
	if err := chip.ResetFIFO(); err != nil {
		fmt.Printf("reset_fifo: %v\n", err)
	}
	check("fifo_enable_reset", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

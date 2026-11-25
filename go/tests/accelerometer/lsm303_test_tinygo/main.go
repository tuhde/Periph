//go:build tinygo

// LSM303 hardware test — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	accelConn, err := connection.NewI2CConnection(machine.I2C0, accelerometer.LSM303I2CAddressAccel, nil, nil)
	if err != nil {
		fmt.Println("accelerometer connection:", err)
		return
	}
	magConn, err := connection.NewI2CConnection(machine.I2C0, accelerometer.LSM303I2CAddressMag, nil, nil)
	if err != nil {
		fmt.Println("magnetometer connection:", err)
		return
	}
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
		fmt.Println("init:", err)
		return
	}
	check("construct_full", true)

	ax, ay, az, err := sensor.ReadAcceleration()
	check("read_acceleration_no_error", err == nil)
	check("read_acceleration_returns_floats", ax == ax && ay == ay && az == az)

	mx, my, mz, err := sensor.ReadMagnetic()
	check("read_magnetic_no_error", err == nil)
	check("read_magnetic_returns_floats", mx == mx && my == my && mz == mz)

	check("set_range", sensor.SetRange(4) == nil)
	check("set_data_rate", sensor.SetDataRate(50) == nil)
	check("set_mode", sensor.SetMode(accelerometer.LSM303ModeHighResolution) == nil)
	check("set_axes", sensor.SetAxes(true, true, true) == nil)

	newData, err := sensor.NewDataAvailable()
	check("new_data_available", err == nil && (newData || !newData))
	_, err = sensor.ReadStatus()
	check("read_status", err == nil)

	check("set_high_pass", sensor.SetHighPass(0, 0, false, true, false, false) == nil)
	_, err = sensor.ResetHighPass()
	check("reset_high_pass", err == nil)
	check("set_fifo", sensor.SetFifo(accelerometer.LSM303FifoStream, 16, false) == nil)
	_, err = sensor.FifoLevel()
	check("fifo_level", err == nil)

	check("set_motion_interrupt", sensor.SetMotionInterrupt(1, 0x02, 0.5, 10, false) == nil)
	check("set_free_fall", sensor.SetFreeFall(1, 0.35, 30) == nil)
	check("set_latch", sensor.SetLatch(1, true) == nil)
	_, err = sensor.ReadInterruptSource(1)
	check("read_interrupt_source", err == nil)
	check("set_click", sensor.SetClick(0, 0x04, 0.8, 20, 40, 200) == nil)
	_, err = sensor.ReadClickSource()
	check("read_click_source", err == nil)
	check("enable_interrupt", sensor.EnableInterrupt(accelerometer.LSM303SourceClick, 1) == nil)
	check("disable_interrupt", sensor.DisableInterrupt(accelerometer.LSM303SourceClick, 1) == nil)

	check("set_mag_range", sensor.SetMagRange(250) == nil)
	check("set_mag_data_rate", sensor.SetMagDataRate(30) == nil)
	check("set_mag_mode", sensor.SetMagMode(accelerometer.LSM303MagModeContinuous) == nil)
	identity, err := sensor.ReadMagIdentity()
	check("mag_identity_matches", err == nil && identity == accelerometer.LSM303MagIdentity)
	check("enable_temperature", sensor.EnableTemperature(true) == nil)
	temp, err := sensor.ReadTemperatureDelta()
	check("read_temperature_delta", err == nil)

	heading, err := sensor.Heading(false)
	check("heading_in_range", err == nil && heading >= 0 && heading < 360)

	_, err = sensor.PollInterrupt()
	check("poll_interrupt", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

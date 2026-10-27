//go:build tinygo

// LSM303 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an LSM303DLHC on I2C0 (GP4 = SDA,
// GP5 = SCL), one connection per slave (accelerometer 0x19, magnetometer
// 0x1E). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C0
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	accelConn := connection.NewI2CConnection(i2c, 0x19, nil, nil)
	magConn := connection.NewI2CConnection(i2c, 0x1E, nil, nil)

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

	sensor, err := accelerometer.NewLSM303Minimal(accelConn, magConn)
	if err != nil {
		fmt.Printf("FAIL init: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	check("construct_minimal", true)

	x, y, z, err := sensor.ReadAcceleration()
	check("accel_no_error", err == nil)
	check("accel_finite", err == nil && x == x && y == y && z == z)
	mag := x*x + y*y + z*z
	check("accel_magnitude_near_1g", err == nil && mag >= 0.25 && mag <= 2.25)

	mx, my, mz, err := sensor.ReadMagnetic()
	check("mag_no_error", err == nil)
	check("mag_finite", err == nil && mx == mx && my == my && mz == mz)
	check("mag_magnitude_plausible", err == nil && mx*mx+my*my+mz*mz < 656100)

	full, err := accelerometer.NewLSM303Full(accelConn, magConn)
	if err != nil {
		fmt.Printf("FAIL init full: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
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
		fmt.Printf("set_range: %v\n", err)
		return
	}
	x, y, z, err = full.ReadAcceleration()
	check("read_after_set_range_4g", err == nil && x == x && y == y && z == z)
	if err := full.SetRange(2); err != nil {
		fmt.Printf("set_range back: %v\n", err)
		return
	}

	if err := full.SetDataRate(50); err != nil {
		fmt.Printf("set_data_rate: %v\n", err)
		return
	}
	if err := full.SetDataRate(100); err != nil {
		fmt.Printf("set_data_rate back: %v\n", err)
		return
	}
	check("set_data_rate", true)

	heading, err := full.Heading(true)
	check("heading_no_error", err == nil)
	check("heading_in_0_360", err == nil && heading == heading && heading >= 0 && heading <= 360)

	delta, err := full.ReadTemperatureDelta()
	check("temperature_delta_no_error", err == nil)
	check("temperature_delta_finite", err == nil && delta == delta && delta > -100 && delta < 100)

	watermark, overrun, empty, level, err := full.ReadFifoStatus()
	check("read_fifo_status_no_error", err == nil)
	check("read_fifo_status_plausible",
		err == nil && !watermark && !overrun && level <= 32 && (empty || level > 0))

	if err := full.SetFifo(2, 16, false); err != nil {
		fmt.Printf("set_fifo: %v\n", err)
		return
	}
	level, err = full.FifoLevel()
	check("fifo_level_no_error", err == nil && level <= 32)
	if err := full.SetFifo(0, 0, false); err != nil {
		fmt.Printf("set_fifo bypass: %v\n", err)
		return
	}
	check("set_fifo_stream_and_bypass", true)

	if err := full.SetFreeFall(1, 0.35, 30); err != nil {
		fmt.Printf("set_free_fall: %v\n", err)
		return
	}
	if err := full.SetClick(0, 4, 0.8, 20, 40, 200); err != nil {
		fmt.Printf("set_click: %v\n", err)
		return
	}
	check("configure_free_fall_and_click", true)

	if err := full.EnableInterrupt(accelerometer.SourceClick, 1); err != nil {
		fmt.Printf("enable_interrupt: %v\n", err)
		return
	}
	status, err := full.PollInterrupt()
	check("poll_interrupt_status_masked", err == nil && status&^uint16(0x00FF) == 0)
	check("poll_interrupt_no_error", err == nil)
	if err := full.DisableInterrupt(accelerometer.SourceClick, 1); err != nil {
		fmt.Printf("disable_interrupt: %v\n", err)
		return
	}
	check("enable_disable_interrupt", true)

	if err := full.SetMagRange(400); err != nil {
		fmt.Printf("set_mag_range: %v\n", err)
		return
	}
	mx, my, mz, err = full.ReadMagnetic()
	check("read_after_set_mag_range_400", err == nil && mx == mx && my == my && mz == mz)
	if err := full.SetMagRange(130); err != nil {
		fmt.Printf("set_mag_range back: %v\n", err)
		return
	}
	check("set_mag_range", true)

	if err := full.SetMagDataRate(30); err != nil {
		fmt.Printf("set_mag_data_rate: %v\n", err)
		return
	}
	if err := full.SetMagDataRate(15); err != nil {
		fmt.Printf("set_mag_data_rate back: %v\n", err)
		return
	}
	check("set_mag_data_rate", true)

	if err := full.SetMagMode(0); err != nil {
		fmt.Printf("set_mag_mode: %v\n", err)
		return
	}
	check("set_mag_mode_continuous", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		for {
			time.Sleep(time.Second)
		}
	}
	for {
		time.Sleep(time.Second)
	}
}

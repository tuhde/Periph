//go:build tinygo

// LIS3DH hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, 0x18, nil, nil)
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

	sensor, err := accelerometer.NewLIS3DHMinimal(conn, 0x18)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	check("construct_minimal", true)

	x, y, z, err := sensor.Read()
	check("read_no_error", err == nil)
	check("read_returns_floats", x == x && y == y && z == z)
	mag := math.Sqrt(x*x + y*y + z*z)
	check("magnitude_near_1g", mag >= 0.5 && mag <= 1.5)
	check("magnitude_is_finite", !math.IsInf(mag, 0))

	full, err := accelerometer.NewLIS3DHFull(conn, 0x18)
	if err != nil {
		fmt.Println("init full:", err)
		return
	}
	check("construct_full", true)

	if err := full.SetRange(accelerometer.LIS3DHRange4G); err != nil {
		fmt.Println("set_range:", err)
		return
	}
	check("set_range_4g", true)
	x, y, z, err = full.Read()
	check("read_after_set_range_4g", err == nil && x == x && y == y && z == z)
	mag = math.Sqrt(x*x + y*y + z*z)
	check("magnitude_near_1g_at_4g", mag >= 0.5 && mag <= 1.5)

	if err := full.SetRange(accelerometer.LIS3DHRange2G); err != nil {
		fmt.Println("set_range:", err)
		return
	}
	if err := full.SetDataRate(100); err != nil {
		fmt.Println("set_data_rate:", err)
		return
	}
	check("set_data_rate_100hz", true)

	if err := full.SetMode(accelerometer.LIS3DHModeHighResolution); err != nil {
		fmt.Println("set_mode:", err)
		return
	}
	check("set_mode_high_resolution", true)

	rx, ry, rz, err := full.ReadRaw()
	check("read_raw_no_error", err == nil)
	check("read_raw_in_range", rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 && rz >= -2048 && rz <= 2047)

	ready, err := full.NewDataAvailable()
	check("new_data_available_no_error", err == nil)
	_ = ready

	status, err := full.ReadStatus()
	check("read_status_no_error", err == nil)
	check("status_is_byte", status <= 0xFF)

	temp, err := full.ReadTemperatureDelta()
	check("read_temperature_delta_no_error", err == nil)
	check("temperature_delta_in_range", temp >= -128 && temp <= 127)

	volt, err := full.ReadADC(1)
	check("read_adc_no_error", err == nil)
	check("read_adc_is_finite", !math.IsNaN(volt) && !math.IsInf(volt, 0))

	if err := full.SetFIFO(2, 16, false); err != nil {
		fmt.Println("set_fifo:", err)
		return
	}
	check("set_fifo_stream", true)
	level, err := full.FIFOLevel()
	check("fifo_level_no_error", err == nil)
	check("fifo_level_in_range", level >= 0 && level <= 32)
	samples, err := full.ReadFIFO(0)
	check("read_fifo_no_error", err == nil)
	check("read_fifo_count_matches_level", len(samples) == level)

	if err := full.SetFreeFall(1, 0.35, 60); err != nil {
		fmt.Println("set_free_fall:", err)
		return
	}
	if err := full.SetClick(0, 0x04, 0.8, 20, 40, 200, true); err != nil {
		fmt.Println("set_click:", err)
		return
	}
	check("set_free_fall_and_click", true)

	if err := full.EnableInterrupt(accelerometer.SourceClick, 1); err != nil {
		fmt.Println("enable_interrupt:", err)
		return
	}
	if err := full.EnableInterrupt(accelerometer.SourceIA1, 1); err != nil {
		fmt.Println("enable_interrupt:", err)
		return
	}
	check("enable_interrupt", true)
	irq, err := full.PollInterrupt()
	check("poll_interrupt_no_error", err == nil)
	check("poll_interrupt_mask", irq&^(accelerometer.SourceClick|accelerometer.SourceIA1) == 0)
	if err := full.DisableInterrupt(accelerometer.SourceClick, 1); err != nil {
		fmt.Println("disable_interrupt:", err)
		return
	}
	check("disable_interrupt", true)

	if err := full.SetSDOPullup(true); err != nil {
		fmt.Println("set_sdo_pullup:", err)
		return
	}
	check("set_sdo_pullup", true)

	if err := full.Reboot(); err != nil {
		fmt.Println("reboot:", err)
		return
	}
	check("reboot", true)

	x, y, z, err = full.Read()
	check("read_after_reboot", err == nil && x == x && y == y && z == z)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	for {
		time.Sleep(time.Second)
	}
}

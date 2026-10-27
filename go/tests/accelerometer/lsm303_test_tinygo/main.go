//go:build tinygo

// LSM303 hardware test — TinyGo (Raspberry Pi Pico W).
//
// Requires the LSM303DLHC accelerometer at I²C 0x19 and magnetometer at 0x1E
// on machine.I2C0. Prints PASS/FAIL per check and ===DONE===.
package main

import (
	"machine"
	"math"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, cond bool) {
	if cond {
		println("PASS", label)
		passed++
	} else {
		println("FAIL", label)
		failed++
	}
}

func finite(vs ...float32) bool {
	for _, v := range vs {
		if math.IsNaN(float64(v)) || math.IsInf(float64(v), 0) {
			return false
		}
	}
	return true
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	accelConn := connection.NewI2CConnection(machine.I2C0, 0x19, nil, nil)
	magConn := connection.NewI2CConnection(machine.I2C0, 0x1E, nil, nil)

	sensor, err := accelerometer.NewLSM303Minimal(accelConn, magConn)
	if err != nil {
		println("init:", err.Error())
		return
	}
	check("construct_minimal", true)

	ax, ay, az, err := sensor.ReadAcceleration()
	check("read_acceleration_no_error", err == nil)
	check("read_acceleration_finite", finite(ax, ay, az))
	mag := float32(math.Sqrt(float64(ax*ax + ay*ay + az*az)))
	check("acceleration_magnitude_near_1g", mag >= 0.5 && mag <= 1.5)

	mx, my, mz, err := sensor.ReadMagnetic()
	check("read_magnetic_no_error", err == nil)
	check("read_magnetic_finite", finite(mx, my, mz))

	full, err := accelerometer.NewLSM303Full(accelConn, magConn)
	if err != nil {
		println("init full:", err.Error())
		return
	}
	check("construct_full", true)

	ident, err := full.ReadMagIdentity()
	check("identity_48_34_33", err == nil && len(ident) == 3 && ident[0] == 0x48 && ident[1] == 0x34 && ident[2] == 0x33)

	check("set_range_4", full.SetRange(4) == nil)
	check("set_data_rate_100", full.SetDataRate(100) == nil)
	check("reject_1620_in_high_resolution", full.SetDataRate(1620) != nil)
	check("set_mode_normal", full.SetMode(1) == nil)
	check("set_mode_high_resolution", full.SetMode(2) == nil)
	check("set_axes_all", full.SetAxes(true, true, true) == nil)

	rx, ry, rz, err := full.ReadAccelerationRaw()
	check("read_acceleration_raw_no_error", err == nil)
	check("raw_within_12_bit", rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 && rz >= -2048 && rz <= 2047)

	_, err = full.NewDataAvailable()
	check("new_data_available_no_error", err == nil)
	_, err = full.ReadStatus()
	check("read_status_no_error", err == nil)

	check("set_mag_range_130", full.SetMagRange(130) == nil)
	check("set_mag_data_rate_15", full.SetMagDataRate(15) == nil)
	check("set_mag_mode_continuous", full.SetMagMode(0) == nil)
	_, _, _, err = full.ReadMagneticRaw()
	check("read_magnetic_raw_no_error", err == nil)

	temp, err := full.ReadTemperatureDelta()
	check("read_temperature_no_error", err == nil && finite(temp))

	h, err := full.Heading(true)
	check("heading_in_range", err == nil && h >= 0 && h <= 360)

	check("set_fifo_stream", full.SetFifo(2, 16, false) == nil)
	_, err = full.FifoLevel()
	check("fifo_level_no_error", err == nil)
	_, err = full.ReadFifo(0)
	check("read_fifo_no_error", err == nil)
	check("set_fifo_bypass", full.SetFifo(0, 0, false) == nil)

	status, err := full.PollInterrupt()
	check("poll_interrupt_nothing_enabled", err == nil && status == 0)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}

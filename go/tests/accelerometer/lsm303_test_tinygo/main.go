//go:build tinygo

// LSM303 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

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

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	accelConn, err := connection.NewI2CConnection(0, 0x19, nil, nil)
	if err != nil {
		panic(err)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(0, 0x1E, nil, nil)
	if err != nil {
		panic(err)
	}
	defer magConn.Close()
	chip, err := accelerometer.NewLSM303Full(accelConn, magConn) // Create LSM303 Full driver, (accelConn, magConn) → (*LSM303Full, error)
	if err != nil {
		panic(err)
	}

	id, err := chip.ReadMagIdentity() // Read magnetometer identity, () → ([]byte, error)
	check("mag_identity", err == nil && id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33)

	ax, ay, az, err := chip.ReadAcceleration() // Read 3-axis acceleration, () → (g, g, g, error)
	check("read_acceleration_floats", err == nil && ax == ax && ay == ay && az == az)

	mx, my, mz, err := chip.ReadMagnetic() // Read 3-axis magnetic field, () → (µT, µT, µT, error)
	check("read_magnetic_floats", err == nil && mx == mx && my == my && mz == mz)

	check("set_range_4g", chip.SetRange(4) == nil)
	rx, ry, rz, err := chip.ReadAccelerationRaw() // Read raw acceleration counts, () → (LSB, LSB, LSB, error)
	check("read_acceleration_raw_ints", err == nil && rx == rx && ry == ry && rz == rz)

	check("set_mag_range_470ut", chip.SetMagRange(470) == nil)
	check("enable_temperature", chip.EnableTemperature(true) == nil)
	temp, err := chip.ReadTemperatureDelta() // Read relative temperature, () → (float64, error) °C
	check("temperature_plausible", err == nil && temp == temp)

	check("enable_interrupt", chip.EnableInterrupt(accelerometer.SourceDataReady, 1) == nil)
	_, err = chip.PollInterrupt() // Poll interrupt status, () → (uint16, error)
	check("poll_interrupt", err == nil)
	check("disable_interrupt", chip.DisableInterrupt(accelerometer.SourceDataReady, 1) == nil)

	println("===DONE:", passed, "passed,", failed, "failed===")
}

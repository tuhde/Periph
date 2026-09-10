//go:build tinygo

// ADXL345 hardware test — TinyGo (Raspberry Pi Pico W).
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

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn, err := connection.NewI2CConnection(0, 0x53, nil, nil)
	if err != nil {
		println("connection:", err)
		return
	}
	defer conn.Close()

	sensor, err := accelerometer.NewADXL345Minimal(conn, false)
	if err != nil {
		println("init:", err)
		return
	}
	check("construct_minimal", true)

	x, y, z, err := sensor.Read()
	check("read_no_error", err == nil)
	check("read_returns_floats",
		!math.IsNaN(float64(x)) && !math.IsNaN(float64(y)) && !math.IsNaN(float64(z)))
	mag := float32(math.Sqrt(float64(x*x + y*y + z*z)))
	check("magnitude_near_1g", mag >= 0.5 && mag <= 1.5)

	full, err := accelerometer.NewADXL345Full(conn, false)
	if err != nil {
		println("init full:", err)
		return
	}
	check("construct_full", true)
	if err := full.SetRange(4); err != nil {
		println("set_range:", err)
		return
	}
	x, y, z, err = full.Read()
	check("read_after_set_range_4g",
		err == nil && !math.IsNaN(float64(x)) && !math.IsNaN(float64(y)) && !math.IsNaN(float64(z)))

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}
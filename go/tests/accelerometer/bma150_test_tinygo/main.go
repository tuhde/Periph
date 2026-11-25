//go:build tinygo

// BMA150 hardware test — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	conn, err := connection.NewI2CConnection(machine.I2C0, 0x38, nil, nil)
	if err != nil {
		fmt.Println("connection:", err)
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

	sensor, err := accelerometer.NewBMA150Minimal(conn)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	check("construct_minimal", true)
	x, y, z, err := sensor.Read()
	check("read_no_error", err == nil)
	check("read_returns_floats", x == x && y == y && z == z)

	full, err := accelerometer.NewBMA150Full(conn)
	if err != nil {
		fmt.Println("init full:", err)
		return
	}
	check("construct_full", true)
	if err := full.SetRange(4); err != nil {
		fmt.Println("set_range:", err)
		return
	}
	x, y, z, err = full.Read()
	check("read_after_set_range_4g", err == nil && x == x && y == y && z == z)

	temp, err := full.ReadTemperature()
	check("read_temperature_in_range", err == nil && temp >= -30.0 && temp <= 97.5)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		for {
			time.Sleep(time.Second)
		}
	}
	for {
		time.Sleep(time.Second)
	}
}

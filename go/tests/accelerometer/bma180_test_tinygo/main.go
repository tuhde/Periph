//go:build tinygo

// BMA180 hardware test — TinyGo on Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

const ADDR uint8 = 0x40

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400000})
	conn, err := connection.NewI2CConnection(machine.I2C0, ADDR, nil, nil)
	if err != nil {
		fmt.Println("connection:", err)
		return
	}
	minimal, err := accelerometer.NewBMA180Minimal(conn)
	if err != nil {
		fmt.Println("init minimal:", err)
		return
	}

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

	x, y, z, err := minimal.Read()
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	check("read_returns_three_floats", true)
	mag := (x*x + y*y + z*z)
	check("magnitude_near_1g", mag > 0.25 && mag < 2.25)

	full, err := accelerometer.NewBMA180Full(conn)
	if err != nil {
		fmt.Println("init full:", err)
		return
	}
	if err := full.SetRange(4); err != nil {
		fmt.Println("set range:", err)
		return
	}
	x, y, z, err = full.Read()
	if err != nil {
		fmt.Println("read full:", err)
		return
	}
	check("read_after_set_range_4g", true)

	temp, err := full.ReadTemperature()
	if err != nil {
		fmt.Println("read temp:", err)
		return
	}
	check("temperature_in_range", temp >= -40.0 && temp <= 87.5)

	al, ml, err := full.ReadVersion()
	if err != nil {
		fmt.Println("read version:", err)
		return
	}
	check("read_version_ok", al <= 0x0F && ml <= 0x0F)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		for {
			time.Sleep(time.Hour)
		}
	}
	for {
		time.Sleep(time.Hour)
	}
}
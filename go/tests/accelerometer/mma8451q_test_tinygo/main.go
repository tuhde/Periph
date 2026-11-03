//go:build tinygo

// MMA8451Q hardware test — TinyGo / Raspberry Pi Pico W.
//
// Exercises the MMA8451Q driver on I2C0, prints PASS/FAIL lines, and prints
// the standard ===DONE: ... === summary.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	conn := connection.NewI2CConnection(machine.I2C0, 0x1C, nil, nil)
	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	chip, err := accelerometer.NewMMA8451QMinimal(conn) // Create MMA8451Q driver, (connection) → (*MMA8451QMinimal, error)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	check("construct_minimal", true)

	x, y, z, err := chip.Read() // Read 3-axis acceleration, () → (x, y, z g, error)
	check("read_no_error", err == nil)
	check("read_returns_floats", err == nil && x == x && y == y && z == z)

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

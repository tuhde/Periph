//go:build tinygo

// DRV8830 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/motor"
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
	conn := connection.NewI2CConnection(machine.I2C0, motor.DRV8830I2CAddress, nil, nil)
	defer conn.Close()

	m, err := motor.NewDRV8830Minimal(conn)
	if err != nil {
		println("init:", err)
		return
	}
	check("construct_minimal", m != nil)
	check("stop_no_error", m.Stop() == nil)

	full, err := motor.NewDRV8830Full(conn)
	if err != nil {
		println("init:", err)
		return
	}
	check("drive_forward_no_error", full.Drive(2.0) == nil)
	v, dir, err := full.ReadOutput()
	check("drive_forward_direction", err == nil && dir == motor.DRV8830Forward)
	check("drive_forward_voltage", v > 1.9 && v < 2.1)

	check("drive_reverse_no_error", full.Drive(-1.0) == nil)
	_, dir, err = full.ReadOutput()
	check("drive_reverse_direction", err == nil && dir == motor.DRV8830Reverse)

	check("brake_no_error", full.Brake() == nil)
	_, dir, err = full.ReadOutput()
	check("brake_direction", err == nil && dir == motor.DRV8830Brake)

	check("stop_no_error_full", full.Stop() == nil)
	_, dir, err = full.ReadOutput()
	check("stop_direction", err == nil && dir == motor.DRV8830Coast)

	check("clear_fault_no_error", full.ClearFault() == nil)
	f, err := full.ReadFault()
	check("clear_fault", err == nil && !f.Fault)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}

//go:build linux && !tinygo

// DRV8830 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

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

	m, err := motor.NewDRV8830Minimal(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("construct_minimal", m != nil)
	check("stop_no_error", m.Stop() == nil)

	full, err := motor.NewDRV8830Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
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

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

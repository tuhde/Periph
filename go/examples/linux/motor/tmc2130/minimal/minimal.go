//go:build linux && !tinygo

// TMC2130 minimal example — Linux host. Move a stepper with STEP/DIR pulses.
//
// Wiring: SPI Mode 3, 1 MHz; STEP/DIR/ENN on GPIO 27/22/17, DIAG0 on GPIO 23.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

func run(conn connection.Connection, _, step, dir connection.OutputPin, _ connection.InputPin) {
	m, err := motor.NewTMC2130Minimal(conn, step, dir, 0.11, nil) // Create TMC2130 driver, (connection, stepPin, dirPin, rSenseOhm=0.11 Ω, enPin=nil) → (*TMC2130Minimal, error)
	check(err)

	check(m.SetCurrent(0.8)) // Set run current, (rmsA A RMS) → error
	check(m.Enable())        // Power stage on, () → error
	for _, steps := range []int{400, -400} {
		check(m.Move(steps, 200)) // Move, (fullSteps, fullStepsPerS full steps/s) → error
		time.Sleep(500 * time.Millisecond)
		standstill, err := m.IsStandstill() // Read standstill flag, () → (bool, error)
		check(err)
		fault, err := m.ReadFault() // Read fault bitmask, () → (uint8, error)
		check(err)
		fmt.Printf("standstill=%v fault=%d\n", standstill, fault)
	}
	check(m.Disable()) // Power stage off, () → error
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func exit(code int) { os.Exit(code) }

func envInt(name string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(name)); err == nil {
		return v
	}
	return def
}

func main() {
	conn, err := connection.NewSPIConnection(envInt("SPI_BUS", 0), envInt("SPI_DEVICE", 0), 3, 1_000_000, nil, nil) // Create SPI connection, (bus, device, mode=3, max_speed=1 MHz, intPin, enPin) → (*SPIConnection, error)
	check(err)
	defer conn.Close()
	en, err := connection.NewGpioOutputPin(17) // Create ENN pin, (gpio line) → (*GpioOutputPin, error)
	check(err)
	step, err := connection.NewGpioOutputPin(27) // Create STEP pin, (gpio line) → (*GpioOutputPin, error)
	check(err)
	dir, err := connection.NewGpioOutputPin(22) // Create DIR pin, (gpio line) → (*GpioOutputPin, error)
	check(err)
	diag0, err := connection.NewGpioInputPin(23) // Create DIAG0 pin, (gpio line) → (*GpioInputPin, error)
	check(err)
	run(conn, en, step, dir, diag0)
}

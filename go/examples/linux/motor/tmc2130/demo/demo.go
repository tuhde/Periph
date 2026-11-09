//go:build linux && !tinygo

// TMC2130 demo example — Linux host. Sensorless homing of a linear axis with StallGuard2.
//
// Wiring: SPI Mode 3, 1 MHz; STEP/DIR/ENN on GPIO 27/22/17, DIAG0 on GPIO 23.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	homingSpeed = 150
	burst       = 20
)

var m *motor.TMC2130Full

func guard() {
	// --- Stop on any driver fault ---
	// Short circuits and over-temperature latch until the driver is disabled,
	// so the demo shuts everything down and reports which fault fired.
	fault, err := m.ReadFault() // Read fault bitmask, () → (uint8, error)
	check(err)
	if fault != 0 {
		m.Disable() // Power stage off, () → error
		fmt.Printf("fault 0x%02X\n", fault)
		exit(1)
	}
}

func run(conn connection.Connection, en, step, dir connection.OutputPin, _ connection.InputPin) {
	var err error
	m, err = motor.NewTMC2130Full(conn, step, dir, 0.11, en, nil, nil) // Create TMC2130 Full driver, (connection, stepPin, dirPin, rSenseOhm=0.11 Ω, enPin, diag0, diag1) → (*TMC2130Full, error)
	check(err)

	// --- Bring up the link and the power stage ---
	// IOIN.VERSION proves the SPI wiring and Mode 3 setting work before any
	// motion is attempted.
	ioin, err := m.ReadIoin() // Read IOIN, () → (TMC2130Ioin, error)
	check(err)
	fmt.Printf("version 0x%02X\n", ioin.Version)
	check(m.SetCurrent(0.8))   // Set run current, (rmsA A RMS) → error
	check(m.SetMicrosteps(16)) // Set microstep resolution, (n 1–256) → error
	check(m.Enable())          // Power stage on, () → error

	// --- Arm StallGuard2 for the homing speed ---
	// StallGuard2 needs SpreadCycle and TSTEP <= TCOOLTHRS, so TCOOLTHRS is set
	// just above the TSTEP of the homing speed; sgt = 0 is the usual start value.
	check(m.SetChopperMode(true))                                     // Choose chopper, (spreadcycle) → error
	check(m.SetStallThreshold(0))                                     // Set StallGuard2 threshold, (sgt -64..63) → error
	check(m.SetCoolstepThreshold(12000000 / (homingSpeed * 256 / 2))) // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → error

	// --- Drive toward the end stop in short bursts ---
	// The first burst is spin-up, where SG_RESULT is not yet valid; after each
	// burst the demo reads SG_RESULT until the stall flag is set.
	var history []uint16
	check(m.Move(burst, homingSpeed)) // Move, (fullSteps, fullStepsPerS full steps/s) → error
	for {
		check(m.Move(burst, homingSpeed)) // Move, (fullSteps, fullStepsPerS full steps/s) → error
		sg, err := m.ReadStallguard()     // Read StallGuard2 result, () → (uint16 0–1023, error)
		check(err)
		history = append(history, sg)
		fmt.Printf("SG_RESULT %d\n", sg)
		stalled, err := m.IsStalled() // Read stall flag, () → (bool, error)
		check(err)
		if stalled {
			break
		}
		guard()
	}

	// --- End stop found: report and back off ---
	// The last few SG_RESULT values show how the load built up before the stall.
	if len(history) > 5 {
		history = history[len(history)-5:]
	}
	fmt.Printf("history %v\n", history)
	check(m.Move(-200, homingSpeed)) // Move, (fullSteps, fullStepsPerS full steps/s) → error

	// --- Final move with CoolStep ---
	// CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
	check(m.ConfigureCoolstep(5, 2, 1, 1, 0)) // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → error
	for i := 0; i < 10; i++ {
		check(m.Move(20, homingSpeed))  // Move, (fullSteps, fullStepsPerS full steps/s) → error
		cs, err := m.ReadCurrentScale() // Read actual current scale, () → (uint8 0–31, error)
		check(err)
		fmt.Printf("CS_ACTUAL %d\n", cs)
		guard()
	}

	// --- Shut down and report ---
	// GSTAT shows whether the run stressed the driver; the chip clears it on read.
	st, err := m.ReadStatus() // Read driver status, () → (TMC2130Status, error)
	check(err)
	gs, err := m.ReadGlobalStatus() // Read GSTAT, () → (TMC2130GlobalStatus, error)
	check(err)
	fmt.Printf("status %+v, global status %+v\n", st, gs)
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

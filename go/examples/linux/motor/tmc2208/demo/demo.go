//go:build linux && !tinygo

// TMC2208 demo example — Linux host. Silent desk-fan / camera-slider controller using the internal step generator.
//
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).
package main

import (
	"fmt"
	"os"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	microsteps  = 16
	spreadAbove = 400
)

var m *motor.TMC2208Full

func guard() {
	// --- Stop on any driver fault ---
	// Short circuits and over-temperature latch until the driver is disabled,
	// so the demo shuts everything down and reports which fault fired.
	fault, err := m.ReadFault() // Read fault bitmask, () → (uint8, error)
	check(err)
	if fault != 0 {
		m.Stop()    // Stop the motor, () → error
		m.Disable() // Power stage off, () → error
		fmt.Printf("fault 0x%02X\n", fault)
		exit(1)
	}
}

func ramp(from, to int, sign float32) {
	// --- Ramp the internal generator in 100 steps/s increments ---
	// VACTUAL has no acceleration of its own, so the demo steps the speed
	// and reports whether the chip is in StealthChop or SpreadCycle.
	inc := 100
	if to < from {
		inc = -100
	}
	for speed := from; ; speed += inc {
		check(m.Run(sign * float32(speed))) // Spin with the internal generator, (stepsPerS full steps/s) → error
		time.Sleep(500 * time.Millisecond)
		st, err := m.ReadStatus() // Read driver status, () → (TMC2208Status, error)
		check(err)
		mode := "spread"
		if st.Stealth {
			mode = "stealth"
		}
		fmt.Printf("%d steps/s %s CS=%d\n", speed, mode, st.CSActual)
		guard()
		if speed == to {
			break
		}
	}
}

func run(conn connection.Connection, en, _, _ connection.OutputPin) {
	var err error
	m, err = motor.NewTMC2208Full(conn, 0.11, en, true, nil, nil, 12000000, false) // Create TMC2208 Full driver, (connection, rSenseOhm=0.11 Ω, enPin) → (*TMC2208Full, error)
	check(err)

	// --- Bring up the link and the power stage ---
	// IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
	// before any motion is attempted.
	ioin, err := m.ReadIoin() // Read IOIN, () → (TMC2208Ioin, error)
	check(err)
	ifcnt, err := m.ReadIfcnt() // Read write counter, () → (uint8, error)
	check(err)
	fmt.Printf("version 0x%02X, IFCNT %d\n", ioin.Version, ifcnt)
	check(m.SetCurrent(0.8))           // Set run current, (rmsA A RMS) → error
	check(m.SetMicrosteps(microsteps)) // Set microstep resolution, (n 1–256) → error
	check(m.Enable())                  // Power stage on, () → error

	// --- Quiet at low speed, strong at high speed ---
	// StealthChop is silent but loses torque at speed; TPWMTHRS makes the chip
	// change to SpreadCycle once TSTEP drops below the value for 400 steps/s.
	check(m.SetStealthchopThreshold(12000000 / (spreadAbove * 256))) // Set StealthChop velocity limit, (tpwmthrs 20 bit) → error

	for _, sign := range []float32{1, -1} {
		ramp(0, 600, sign)
		time.Sleep(3 * time.Second)
		ramp(600, 0, sign)
		check(m.Stop()) // Stop the motor, () → error
		time.Sleep(time.Second)
	}

	// --- Shut down and report ---
	// Temperature comparators and GSTAT show whether the run stressed the driver.
	temp, err := m.ReadTemperatureFlags() // Read temperature flags, () → (uint8 0–15, error)
	check(err)
	gstat, err := m.ReadGlobalStatus(false) // Read GSTAT, (clear) → (TMC2208GlobalStatus, error)
	check(err)
	fmt.Printf("temperature flags %d, global status %+v\n", temp, gstat)
	check(m.Disable()) // Power stage off, () → error
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func exit(code int) { os.Exit(code) }

func main() {
	port := "/dev/ttyS0"
	if p := os.Getenv("UART_PORT"); p != "" {
		port = p
	}
	conn, err := connection.NewUARTConnection(port, 115200, -1, nil, nil) // Create UART connection, (path, baudRate=115200, dePinNum=-1, intPin, enPin) → (*UARTConnection, error)
	check(err)
	defer conn.Close()
	en, err := connection.NewGpioOutputPin(17) // Create ENN pin, (gpio line) → (*GpioOutputPin, error)
	check(err)
	run(conn, en, nil, nil)
}

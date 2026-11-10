//go:build linux && !tinygo

// TMC5160 demo example — Linux host. Positioning moves with the internal ramp generator.
//
// Wiring: SPI Mode 3, 1 MHz; DRV_ENN on GPIO 17, DIAG0 on GPIO 23.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = time.Second

func run(conn connection.Connection, en connection.OutputPin, diag0 connection.InputPin) {
	// --- Pick-and-place axis: smooth trapezoid between two stations ---
	// The internal ramp generator accelerates at 400 full steps/s² up to 600 full steps/s;
	// StealthChop keeps the axis quiet and StallGuard2 reports a crash against an end stop.
	m, err := motor.NewTMC5160Full(conn, 0.075, en, diag0, nil) // Create TMC5160 Full driver, (connection, rSenseOhm=0.075 Ω, enPin, diag0, diag1) → (*TMC5160Full, error)
	check(err)
	check(m.SetCurrent(1.2))      // Set run current, (rmsA A RMS) → error
	check(m.SetSpeed(600))        // Set VMAX, (stepsPerS full steps/s) → error
	check(m.SetAcceleration(400)) // Set acceleration, (stepsPerS2 full steps/s²) → error
	check(m.SetStallThreshold(2)) // Set StallGuard2 threshold, (sgt -64..63) → error
	check(m.Enable())             // Power stage on, () → error
	check(m.SetPosition(0))       // Define position, (position full steps) → error

	// --- Shuttle between the stations and report each stop ---
	for _, station := range []float32{1000, 0, 1000, 0} {
		check(m.MoveTo(station))     // Start positioning move, (position full steps) → error
		check(m.WaitUntilReached(0)) // Wait for the ramp generator, (timeoutS=0 s) → error
		pos, _ := m.Position()       // Read XACTUAL, () → (float32 full steps, error)
		sg, _ := m.ReadStallguard()  // Read SG_RESULT, () → (uint16, error)
		fmt.Printf("at %v (SG %d)\n", pos, sg)
		fault, _ := m.ReadFault() // Read fault bitmask, () → (uint8, error)
		if fault != 0 {
			fmt.Println("driver fault, aborting")
			break
		}
	}
	check(m.Disable()) // Power stage off, () → error
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

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
	en, err := connection.NewGpioOutputPin(17) // Create DRV_ENN pin, (gpio line) → (*GpioOutputPin, error)
	check(err)
	diag0, err := connection.NewGpioInputPin(23) // Create DIAG0 pin, (gpio line) → (*GpioInputPin, error)
	check(err)
	run(conn, en, diag0)
}

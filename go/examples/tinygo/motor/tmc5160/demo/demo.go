//go:build tinygo

// TMC5160 demo example — TinyGo / Raspberry Pi Pico W. Positioning moves with the internal ramp generator.
//
// SPI0 on GP18 (SCK) / GP19 (MOSI) / GP16 (MISO) / GP17 (CS), Mode 3; DRV_ENN on GP10, DIAG0 on GP13.
package main

import (
	"fmt"
	"machine"
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
		println("error:", err.Error())
		for {
			time.Sleep(time.Second)
		}
	}
}

func main() {
	spi := machine.SPI0
	check(spi.Configure(machine.SPIConfig{Frequency: 1_000_000, SCK: machine.GP18, SDO: machine.GP19, SDI: machine.GP16, Mode: 3}))
	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17, intPin, enPin) → *SPIConnection
	en := connection.NewGpioOutputPin(machine.GP10)                  // Create DRV_ENN pin, (machine.Pin) → *GpioOutputPin
	diag0 := connection.NewGpioInputPin(machine.GP13)                // Create DIAG0 pin, (machine.Pin) → *GpioInputPin
	run(conn, en, diag0)
}

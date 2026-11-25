//go:build tinygo

// TMC2130 minimal example — TinyGo / Raspberry Pi Pico W. Move a stepper with STEP/DIR pulses.
//
// SPI0 on GP18 (SCK) / GP19 (MOSI) / GP16 (MISO) / GP17 (CS), Mode 3; STEP/DIR/ENN/DIAG0 on GP11/GP12/GP10/GP13.
package main

import (
	"fmt"
	"machine"
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
		println("error:", err.Error())
		for {
			time.Sleep(time.Second)
		}
	}
}

func exit(code int) {
	for {
		time.Sleep(time.Second)
	}
}

func main() {
	spi := machine.SPI0
	check(spi.Configure(machine.SPIConfig{Frequency: 1_000_000, SCK: machine.GP18, SDO: machine.GP19, SDI: machine.GP16, Mode: 3}))
	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17, intPin, enPin) → *SPIConnection
	en := connection.NewGpioOutputPin(machine.GP10)                  // Create ENN pin, (machine.Pin) → *GpioOutputPin
	step := connection.NewGpioOutputPin(machine.GP11)                // Create STEP pin, (machine.Pin) → *GpioOutputPin
	dir := connection.NewGpioOutputPin(machine.GP12)                 // Create DIR pin, (machine.Pin) → *GpioOutputPin
	diag0 := connection.NewGpioInputPin(machine.GP13)                // Create DIAG0 pin, (machine.Pin) → *GpioInputPin
	run(conn, en, step, dir, diag0)
}

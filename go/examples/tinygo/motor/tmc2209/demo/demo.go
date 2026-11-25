//go:build tinygo

// TMC2209 demo example — TinyGo / Raspberry Pi Pico W. Sensorless homing of a linear axis with StallGuard4.
//
// Enables the driver, proves the UART link via IOIN.VERSION (0x21) and IFCNT,
// drives toward the end stop at 200 full steps/s until SG_RESULT <= 2*SGTHRS,
// backs off for 2 s and finishes with a CoolStep move.
//
// UART1 on GP4 (TX) / GP5 (RX). Wiring: GP4 -> 1 kOhm -> PDN_UART, GP5 -> PDN_UART directly.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	microsteps  = 16
	homingSpeed = 200
	sgthrs      = 60
)

var m *motor.TMC2209Full

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

func run(conn connection.Connection, en, _, _ connection.OutputPin) {
	var err error
	m, err = motor.NewTMC2209Full(conn, 0.11, 0, en, true, nil, nil, nil, false, 12000000) // Create TMC2209 Full driver, (connection, rSenseOhm=0.11 Ω, address=0, enPin) → (*TMC2209Full, error)
	check(err)

	// --- Bring up the link and the power stage ---
	// IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
	// before any motion is attempted.
	ioin, err := m.ReadIoin() // Read IOIN, () → (TMC2209Ioin, error)
	check(err)
	ifcnt, err := m.ReadIfcnt() // Read write counter, () → (uint8, error)
	check(err)
	fmt.Printf("version 0x%02X, IFCNT %d\n", ioin.Version, ifcnt)
	check(m.SetCurrent(0.8))           // Set run current, (rmsA A RMS) → error
	check(m.SetMicrosteps(microsteps)) // Set microstep resolution, (n 1–256) → error
	check(m.Enable())                  // Power stage on, () → error

	// --- Arm StallGuard4 for the homing speed ---
	// StallGuard needs StealthChop and TCOOLTHRS >= TSTEP > TPWMTHRS; TCOOLTHRS
	// is set just below the TSTEP of the homing speed so detection is active.
	check(m.SetStallThreshold(sgthrs))                                // Set StallGuard4 threshold, (sgthrs 0–255) → error
	check(m.SetCoolstepThreshold(12000000 / (homingSpeed * 256 / 2))) // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → error

	// --- Drive toward the end stop ---
	// The first 0.5 s is spin-up, where SG_RESULT is not yet valid; afterwards
	// the demo polls SG_RESULT every 100 ms until it drops to 2 * SGTHRS.
	check(m.Run(homingSpeed)) // Spin with the internal generator, (stepsPerS full steps/s) → error
	time.Sleep(500 * time.Millisecond)
	var sg uint16
	for {
		sg, err = m.ReadStallguard() // Read StallGuard4 result, () → (uint16 0–510, error)
		check(err)
		fmt.Printf("SG_RESULT %d\n", sg)
		if int(sg) <= 2*sgthrs {
			break
		}
		guard()
		time.Sleep(100 * time.Millisecond)
	}

	// --- End stop found: stop and back off ---
	// Reversing for 2 s moves the axis clear of the stop.
	check(m.Stop()) // Stop the motor, () → error
	fmt.Printf("stalled at SG_RESULT %d\n", sg)
	check(m.Run(-homingSpeed)) // Spin with the internal generator, (stepsPerS full steps/s) → error
	time.Sleep(2 * time.Second)
	check(m.Stop()) // Stop the motor, () → error

	// --- Final move with CoolStep ---
	// CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
	check(m.ConfigureCoolstep(5, 2, 1, 1, 0)) // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → error
	check(m.Run(homingSpeed))                 // Spin with the internal generator, (stepsPerS full steps/s) → error
	for i := 0; i < 10; i++ {
		time.Sleep(500 * time.Millisecond)
		cs, err := m.ReadCurrentScale() // Read actual current scale, () → (uint8 0–31, error)
		check(err)
		fmt.Printf("CS_ACTUAL %d\n", cs)
		guard()
	}
	check(m.Stop()) // Stop the motor, () → error

	// --- Shut down and report ---
	// Temperature comparators and GSTAT show whether the run stressed the driver.
	temp, err := m.ReadTemperatureFlags() // Read temperature flags, () → (uint8 0–15, error)
	check(err)
	gstat, err := m.ReadGlobalStatus(false) // Read GSTAT, (clear) → (TMC2209GlobalStatus, error)
	check(err)
	fmt.Printf("temperature flags %d, global status %+v\n", temp, gstat)
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
	uart := machine.UART1
	check(uart.Configure(machine.UARTConfig{BaudRate: 115200, TX: machine.GP4, RX: machine.GP5}))
	conn := connection.NewUARTConnection(uart, machine.NoPin, nil, nil) // Create UART connection, (uart, dePin=NoPin, intPin, enPin) → *UARTConnection
	en := connection.NewGpioOutputPin(machine.GP10)                     // Create ENN pin, (machine.Pin) → *GpioOutputPin
	run(conn, en, nil, nil)
}

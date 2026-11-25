//go:build linux && !tinygo

// TMC2209 minimal example — Linux host. Spin a stepper with the internal step generator.
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

func run(conn connection.Connection, _, _, _ connection.OutputPin) {
	m, err := motor.NewTMC2209Minimal(conn, 0.11, 0, nil, true) // Create TMC2209 driver, (connection, rSenseOhm=0.11 Ω, address=0, enPin=nil, echo=true) → (*TMC2209Minimal, error)
	check(err)

	check(m.SetCurrent(0.8)) // Set run current, (rmsA A RMS) → error
	for _, speed := range []float32{200, -200} {
		check(m.Run(speed)) // Spin with the internal generator, (stepsPerS full steps/s, sign = direction) → error
		time.Sleep(3 * time.Second)
		standstill, err := m.IsStandstill() // Read standstill flag, () → (bool, error)
		check(err)
		fault, err := m.ReadFault() // Read fault bitmask, () → (uint8, error)
		check(err)
		fmt.Printf("standstill=%v fault=%d\n", standstill, fault)
	}

	check(m.Stop()) // Stop the motor, () → error
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
	run(conn, nil, nil, nil)
}

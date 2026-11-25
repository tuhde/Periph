//go:build tinygo

// TMC2209 minimal example — TinyGo / Raspberry Pi Pico W. Spin a stepper with the internal step generator.
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
	run(conn, nil, nil, nil)
}

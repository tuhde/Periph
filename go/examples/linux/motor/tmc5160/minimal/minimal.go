//go:build linux && !tinygo

// TMC5160 minimal example — Linux host. Positioning moves with the internal ramp generator.
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

func run(conn connection.Connection, en connection.OutputPin) {
	m, err := motor.NewTMC5160Minimal(conn, 0.075, en) // Create TMC5160 driver, (connection, rSenseOhm=0.075 Ω, enPin) → (*TMC5160Minimal, error)
	check(err)

	check(m.SetCurrent(1.0)) // Set run current, (rmsA A RMS) → error
	check(m.Enable())        // Power stage on, () → error
	for _, target := range []float32{400, 0} {
		check(m.MoveTo(target))      // Start positioning move, (position full steps) → error
		check(m.WaitUntilReached(0)) // Wait for the ramp generator, (timeoutS=0 s, 0 = estimate) → error
		pos, err := m.Position()     // Read XACTUAL, () → (float32 full steps, error)
		check(err)
		fault, err := m.ReadFault() // Read fault bitmask, () → (uint8, error)
		check(err)
		fmt.Printf("position=%v fault=%d\n", pos, fault)
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
	run(conn, en)
}

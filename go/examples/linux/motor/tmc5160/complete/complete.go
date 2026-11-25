//go:build linux && !tinygo

// TMC5160 complete example — Linux host. Positioning moves with the internal ramp generator.
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
	m, err := motor.NewTMC5160Full(conn, 0.075, en, diag0, nil) // Create TMC5160 Full driver, (connection, rSenseOhm=0.075 Ω, enPin, diag0, diag1) → (*TMC5160Full, error)
	check(err)

	check(m.SetCurrent(1.0)) // Set run current, (rmsA A RMS) → error
	// programs GLOBALSCALER and IRUN/IHOLD
	check(m.SetHoldCurrent(0.3))                                // Set hold current, (rmsA A RMS) → error
	check(m.SetMicrosteps(256))                                 // Set microstep resolution, (n 1–256) → error
	check(m.ConfigureRamp(0, 800, 400, 400, 400, 400, 800, 10)) // Program SixPoint ramp, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → error
	check(m.SetZeroWait(0.1))                                   // Set TZEROWAIT, (seconds s) → error
	check(m.SetStallThreshold(0))                               // Set StallGuard2 threshold, (sgt -64..63) → error
	check(m.Enable())                                           // Power stage on, () → error
	check(m.SetPosition(0))                                     // Define position, (position full steps) → error
	check(m.MoveTo(800))                                        // Start positioning move, (position full steps) → error
	check(m.WaitUntilReached(0))                                // Wait for the ramp generator, (timeoutS=0 s) → error
	pos, _ := m.Position()                                      // Read XACTUAL, () → (float32 full steps, error)
	vel, _ := m.Velocity()                                      // Read VACTUAL, () → (float32 full steps/s, error)
	ramp, _ := m.ReadRampStatus()                               // Read RAMP_STAT, () → (TMC5160RampStatus, error)
	st, _ := m.ReadStatus()                                     // Read DRV_STATUS, () → (TMC5160Status, error)
	tstep, _ := m.ReadTStep()                                   // Read TSTEP, () → (uint32, error)
	gs, _ := m.ReadGlobalStatus()                               // Read GSTAT, () → (TMC5160GlobalStatus, error)
	fmt.Printf("pos=%v vel=%v reached=%v sg=%d tstep=%d reset=%v\n", pos, vel, ramp.PositionReached, st.SGResult, tstep, gs.Reset)
	check(m.Run(300)) // Velocity mode, (stepsPerS full steps/s) → error
	time.Sleep(500 * time.Millisecond)
	check(m.Stop())    // Controlled stop, () → error
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

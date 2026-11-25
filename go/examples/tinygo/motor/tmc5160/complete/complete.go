//go:build tinygo

// TMC5160 complete example — TinyGo / Raspberry Pi Pico W. Positioning moves with the internal ramp generator.
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

//go:build tinygo

// TMC2130 complete example — TinyGo / Raspberry Pi Pico W. Exercise every method in the public API.
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

func run(conn connection.Connection, en, step, dir connection.OutputPin, diag0 connection.InputPin) {
	m, err := motor.NewTMC2130Full(conn, step, dir, 0.11, en, diag0, nil) // Create TMC2130 Full driver, (connection, stepPin, dirPin, rSenseOhm=0.11 Ω, enPin, diag0, diag1) → (*TMC2130Full, error)
	check(err)
	// reads IOIN to verify the chip, writes the default configuration with mirror checks

	check(m.Enable()) // Power stage on, () → error
	// drives ENN low, waits 100 ms
	check(m.SetCurrent(0.8)) // Set run current, (rmsA A RMS) → error
	// converts amperes to the 0–31 scale, picks vsense automatically
	check(m.SetHoldCurrent(0.2)) // Set hold current, (rmsA A RMS) → error
	// overrides the automatic one-third hold current
	check(m.SetCurrentDelay(6, 10)) // Set power-down timing, (iholdDelay 0–15, tpowerdown 0–255) → error
	// IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
	check(m.SetMicrosteps(32)) // Set microstep resolution, (n 1–256) → error
	// writes CHOPCONF.MRES
	check(m.SetInterpolation(true)) // Interpolate to 256 microsteps, (enabled) → error
	// sets CHOPCONF.intpol
	check(m.SetChopperMode(false)) // Choose chopper, (spreadcycle) → error
	// false enables StealthChop below TPWMTHRS
	check(m.SetStealthchopThreshold(500)) // Set StealthChop velocity limit, (tpwmthrs 20 bit) → error
	// faster than this the chip switches to SpreadCycle
	check(m.SetHighVelocityThreshold(0)) // Set THIGH, (thigh 20 bit) → error
	// 0 keeps the high-velocity features off
	check(m.ConfigureChopper(3, 2, 4, 1)) // Configure SpreadCycle timing, (toff, tbl, hstrt, hend) → error
	// direct CHOPCONF fields with chm = 0
	check(m.ConfigureConstantOffTime(3, 2, 4, 0, false, false)) // Configure constant off time, (toff, tbl, tfd, offset, disfdcc, rndtf) → error
	// chm = 1 with fast decay
	check(m.ConfigureChopper(3, 2, 4, 1)) // Back to SpreadCycle, (toff, tbl, hstrt, hend) → error
	// chm = 0 again
	check(m.ConfigureHighVelocity(false, false)) // Behaviour above THIGH, (vhighchm, vhighfs) → error
	// both off
	check(m.SetChopperSync(0)) // Set chopSync2, (sync 0–15) → error
	// 0 = off
	check(m.ConfigureStealthchop(true, false, 200, 1, 0)) // Configure StealthChop, (autoscale, symmetric, pwmAmpl, pwmGrad, pwmFreq) → error
	// direct PWMCONF fields
	check(m.SetFreewheel(0)) // Set standstill mode, (mode 0–3) → error
	// PWMCONF.freewheel, only effective when IHOLD is 0
	check(m.SetVsense(false)) // Select sense range, (highSensitivity) → error
	// false keeps the 0.325 V full scale; disables automatic selection
	check(m.SetInternalRsense(false)) // Internal sense resistors, (enabled) → error
	// keep false on boards with external resistors
	check(m.SetAnalogScaling(false)) // Current reference source, (enabled) → error
	// false uses the internal reference
	check(m.SetDirectionInverted(false)) // Invert direction, (inverted) → error
	// GCONF.shaft
	check(m.ConfigureDiag(motor.TMC2130DiagConfig{Diag0Stall: true, Diag1Index: true})) // Route events to DIAG pins, (config) → error
	// GCONF bits 5–13
	check(m.SetDoubleEdge(false)) // Step on both edges, (enabled) → error
	// CHOPCONF.dedge
	check(m.SetShortProtection(true)) // Short-to-GND protection, (shortGND) → error
	// true keeps the protection enabled
	check(m.SetSmallHysteresis(false)) // Comparison hysteresis, (enabled) → error
	// false = 1/16
	check(m.SetEmergencyStop(false)) // DCIN emergency stop, (enabled) → error
	// GCONF.stop_enable
	check(m.SetStallThreshold(0)) // Set StallGuard2 threshold, (sgt -64..63) → error
	// lower = more sensitive
	check(m.SetStallguardFilter(false)) // StallGuard2 filter, (enabled) → error
	// COOLCONF.sfilt
	check(m.SetCoolstepThreshold(1000)) // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → error
	// active while TCOOLTHRS >= TSTEP >= THIGH
	check(m.ConfigureCoolstep(5, 2, 1, 1, 0)) // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → error
	// semin = 0 would disable CoolStep

	check(m.Move(200, 150)) // Move, (fullSteps, fullStepsPerS full steps/s) → error
	// blocking, software-timed STEP pulses
	check(m.Step(400, time.Millisecond)) // Pulse the STEP pin, (count, delay) → error
	// negative counts reverse via DIR
	pos, err := m.MicrostepPosition() // Read microstep counter, () → (uint16 0–1023, error)
	check(err)
	// MSCNT
	ca, cb, err := m.ReadCoilCurrents() // Read coil currents, () → (curA, curB int16, error)
	check(err)
	// signed (CUR_A, CUR_B) from MSCURACT
	tstep, err := m.ReadTstep() // Read step interval, () → (uint32 clocks, error)
	check(err)
	// time between 1/256 microsteps
	scale, err := m.ReadCurrentScale() // Read actual current scale, () → (uint8 0–31, error)
	check(err)
	// DRV_STATUS.CS_ACTUAL
	sg, err := m.ReadStallguard() // Read StallGuard2 result, () → (uint16 0–1023, error)
	check(err)
	// SG_RESULT, 0 = highest load
	stalled, err := m.IsStalled() // Read stall flag, () → (bool, error)
	check(err)
	// DRV_STATUS.StallGuard
	d0, err := m.ReadDiag(0) // Read DIAG0 pin, (n 0 or 1) → (bool, error)
	check(err)
	// needs the diag pin to be wired
	pwm, err := m.ReadPwmScale() // Read PWM scaler, () → (uint8 0–255, error)
	check(err)
	// PWM_SCALE
	lost, err := m.ReadLostSteps() // Read skipped DcStep steps, () → (uint32, error)
	check(err)
	// LOST_STEPS
	fmt.Printf("position=%d currents=%d,%d tstep=%d scale=%d sg=%d stalled=%v diag0=%v pwm=%d lost=%d\n",
		pos, ca, cb, tstep, scale, sg, stalled, d0, pwm, lost)
	st, err := m.ReadStatus() // Read driver status, () → (TMC2130Status, error)
	check(err)
	// decoded DRV_STATUS
	standstill, err := m.IsStandstill() // Read standstill flag, () → (bool, error)
	check(err)
	// DRV_STATUS.stst
	fault, err := m.ReadFault() // Read fault bitmask, () → (uint8, error)
	check(err)
	// TMC2130Fault* constants, 0 = none
	gs, err := m.ReadGlobalStatus() // Read GSTAT, () → (TMC2130GlobalStatus, error)
	check(err)
	// reset, drv_err, uv_cp; the chip clears them on read
	io, err := m.ReadIoin() // Read IOIN, () → (TMC2130Ioin, error)
	check(err)
	// raw word, pin states and silicon version
	fmt.Printf("status=%+v standstill=%v fault=%d gstat=%+v version=0x%02X\n", st, standstill, fault, gs, io.Version)
	gconf, err := m.ReadRegister(0x00) // Read a raw register, (reg) → (uint32, error)
	check(err)
	// any readable register
	fmt.Printf("gconf=0x%X\n", gconf)
	check(m.WriteRegister(0x13, 500, true)) // Write a raw register, (reg, value, verify) → error
	// here TPWMTHRS again, with the mirror check

	check(m.SetDcstep(0, 0, 0)) // Configure DcStep, (vdcmin, dcTime, dcSG) → error
	// all zero keeps DcStep off
	check(m.SetDirectMode(true)) // Direct coil-current mode, (enabled) → error
	// GCONF.direct_mode
	check(m.SetCoilCurrents(100, 0)) // Write XDIRECT, (coilA, coilB -255..255) → error
	// scaled by IHOLD
	time.Sleep(500 * time.Millisecond)
	check(m.SetDirectMode(false)) // Back to STEP/DIR, (enabled) → error
	// GCONF.direct_mode = 0
	var customTable *[8]uint32 // replace with a table (and mslutsel, mslutstart) to program it
	if customTable != nil && pos == 0 {
		check(m.WriteMicrostepTable(*customTable, 0, 0x00F70000)) // Program the microstep table, (mslut [8]uint32, mslutsel, mslutstart) → error
		// MSLUT0..7, MSLUTSEL, MSLUTSTART; call at MSCNT = 0
	}
	check(m.Disable()) // Power stage off, () → error
	// drives ENN high; the motor freewheels
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

//go:build linux && !tinygo

// TMC2208 complete example — Linux host. Exercise every method in the public api.
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

func run(conn connection.Connection, en, step, dir connection.OutputPin) {
	m, err := motor.NewTMC2208Full(conn, 0.11, en, true, step, dir, 12000000, false) // Create TMC2208 Full driver, (connection, rSenseOhm=0.11 Ω, enPin, echo=true, stepPin, dirPin, fclkHz=12000000 Hz, tmc2224=false) → (*TMC2208Full, error)
	check(err)
	// reads IOIN to verify the chip, writes the default configuration and checks IFCNT

	check(m.Enable()) // Power stage on, () → error
	// drives ENN low
	check(m.SetCurrent(0.8)) // Set run current, (rmsA A RMS) → error
	// converts amperes to the 0–31 scale; hold current becomes a third
	check(m.SetHoldCurrent(0.2)) // Set hold current, (rmsA A RMS) → error
	// overrides the automatic one-third hold current
	check(m.SetCurrentDelay(6, 20)) // Set power-down timing, (iholdDelay 0–15, tpowerdown 0–255) → error
	// IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
	check(m.SetMicrosteps(32)) // Set microstep resolution, (n 1–256) → error
	// writes CHOPCONF.MRES
	check(m.SetInterpolation(true)) // Interpolate to 256 microsteps, (enabled) → error
	// sets CHOPCONF.intpol
	check(m.SetChopperMode(false)) // Choose chopper, (spreadcycle) → error
	// false selects StealthChop
	check(m.SetStealthchopThreshold(300)) // Set StealthChop velocity limit, (tpwmthrs 20 bit) → error
	// faster than this the chip switches to SpreadCycle
	check(m.ConfigureChopper(3, 2, 5, 0)) // Configure chopper timing, (toff, tbl, hstrt, hend) → error
	// direct CHOPCONF fields
	check(m.ConfigureStealthchop(true, true, 36, 14, 1, 4, 12)) // Configure StealthChop, (autoscale, autograd, pwmOfs, pwmGrad, pwmFreq, pwmReg, pwmLim) → error
	// direct PWMCONF fields
	check(m.SetFreewheel(0)) // Set standstill mode, (mode 0–3) → error
	// PWMCONF.freewheel, only effective when IHOLD is 0
	check(m.SetVsense(false)) // Select sense range, (highSensitivity) → error
	// false keeps the 0.325 V full scale
	check(m.SetAnalogScaling(true)) // Current reference source, (enabled) → error
	// true takes the reference from VREF
	check(m.SetDirectionInverted(false)) // Invert direction, (inverted) → error
	// GCONF.shaft
	check(m.SetIndexOutput(0)) // Select INDEX output, (mode 0–2) → error
	// 0 first microstep, 1 pre-warning, 2 step pulses
	check(m.SetDriverProtection(true, true)) // Short protections, (shortLS, shortGND) → error
	// true keeps the protection enabled
	check(m.SetDoubleEdge(false)) // Step on both edges, (enabled) → error
	// CHOPCONF.dedge
	check(m.SetSendDelay(0)) // Set reply delay, (n 0–15) → error
	// NODECONF.SENDDELAY

	check(m.Run(300)) // Spin with the internal generator, (stepsPerS full steps/s) → error
	// converts to VACTUAL using the 12 MHz clock
	time.Sleep(2 * time.Second)
	pos, err := m.MicrostepPosition() // Read microstep counter, () → (uint16 0–1023, error)
	check(err)
	// MSCNT
	lo, hi, err := m.ReadCoilCurrents() // Read coil currents, () → (int16, int16, error)
	check(err)
	// two signed 9-bit MSCURACT fields
	tstep, err := m.ReadTstep() // Read step interval, () → (uint32 clocks, error)
	check(err)
	// time between 1/256 microsteps
	cs, err := m.ReadCurrentScale() // Read actual current scale, () → (uint8 0–31, error)
	check(err)
	// DRV_STATUS.CS_ACTUAL
	temp, err := m.ReadTemperatureFlags() // Read temperature flags, () → (uint8 0–15, error)
	check(err)
	// bit 0 t120 … bit 3 t157
	status, err := m.ReadStatus() // Read driver status, () → (TMC2208Status, error)
	check(err)
	// decoded DRV_STATUS
	standstill, err := m.IsStandstill() // Read standstill flag, () → (bool, error)
	check(err)
	// DRV_STATUS.stst
	fault, err := m.ReadFault() // Read fault bitmask, () → (uint8, error)
	check(err)
	// TMC2208Fault* constants, 0 = none
	fmt.Println("position", pos, "currents", lo, hi, "tstep", tstep, "scale", cs, "temp", temp)
	fmt.Printf("status %+v standstill %v fault %d\n", status, standstill, fault)
	check(m.Stop()) // Stop the motor, () → error
	// VACTUAL = 0, STEP input takes over again

	check(m.Step(400, time.Millisecond)) // Pulse the STEP pin, (count, delay time.Duration) → error
	// negative counts reverse via DIR
	gstat, err := m.ReadGlobalStatus(true) // Read and clear GSTAT, (clear) → (TMC2208GlobalStatus, error)
	check(err)
	// reset, drvErr, uvcp
	ifcnt, err := m.ReadIfcnt() // Read write counter, () → (uint8, error)
	check(err)
	// IFCNT
	ioin, err := m.ReadIoin() // Read IOIN, () → (TMC2208Ioin, error)
	check(err)
	// raw word, SelA and silicon version
	otp, err := m.ReadOtp() // Read OTP image, () → (uint32, error)
	check(err)
	// OTP_READ
	pwm, err := m.ReadPwmAuto() // Read StealthChop tuning, () → (TMC2208PwmAuto, error)
	check(err)
	// PWM_SCALE and PWM_AUTO results
	gconf, err := m.ReadRegister(0x00) // Read a raw register, (reg) → (uint32, error)
	check(err)
	// any readable register
	fmt.Printf("gstat %+v ifcnt %d ioin %+v otp 0x%X pwm %+v gconf 0x%X\n", gstat, ifcnt, ioin, otp, pwm, gconf)
	check(m.WriteRegister(0x13, 300)) // Write a raw register, (reg, value) → error
	// here TPWMTHRS again
	check(m.Disable()) // Power stage off, () → error
	// drives ENN high; the motor freewheels
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
	en, err := connection.NewGpioOutputPin(17) // Create ENN pin, (gpio line) → (*GpioOutputPin, error)
	check(err)
	step, err := connection.NewGpioOutputPin(27) // Create STEP pin, (gpio line) → (*GpioOutputPin, error)
	check(err)
	dir, err := connection.NewGpioOutputPin(22) // Create DIR pin, (gpio line) → (*GpioOutputPin, error)
	check(err)
	run(conn, en, step, dir)
}

//go:build tinygo

// TMC2130 hardware test — TinyGo / Raspberry Pi Pico W smoke test.
//
// SPI0 on GP18 (SCK) / GP19 (MOSI) / GP16 (MISO) / GP17 (CS), Mode 3; STEP/DIR/ENN on GP11/GP12/GP10.
package main

import (
	"machine"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, ok bool) {
	if ok {
		println("PASS", label)
		passed++
	} else {
		println("FAIL", label)
		failed++
	}
}

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{Frequency: 1_000_000, SCK: machine.GP18, SDO: machine.GP19, SDI: machine.GP16, Mode: 3}); err != nil {
		panic(err)
	}
	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil)    // Create SPI connection, (spi, cs=GP17, intPin, enPin) → *SPIConnection
	en := connection.NewGpioOutputPin(machine.GP10)                     // Create ENN pin, (machine.Pin) → *GpioOutputPin
	step := connection.NewGpioOutputPin(machine.GP11)                   // Create STEP pin, (machine.Pin) → *GpioOutputPin
	dir := connection.NewGpioOutputPin(machine.GP12)                    // Create DIR pin, (machine.Pin) → *GpioOutputPin
	m, err := motor.NewTMC2130Full(conn, step, dir, 0.11, en, nil, nil) // Create TMC2130 Full driver, (connection, stepPin, dirPin, rSenseOhm=0.11 Ω, enPin, diag0, diag1) → (*TMC2130Full, error)
	check("init", err == nil)
	if err != nil {
		return
	}

	ioin, _ := m.ReadIoin() // Read IOIN, () → (TMC2130Ioin, error)
	check("ioin_version", ioin.Version == 0x11)
	check("verified_write", m.WriteRegister(0x13, 600, true) == nil) // Write a raw register, (reg, value, verify) → error
	m.SetCurrent(0.5)                                                // Set run current, (rmsA A RMS) → error
	m.SetMicrosteps(16)                                              // Set microstep resolution, (n 1–256) → error
	chop, _ := m.ReadRegister(0x6C)                                  // Read a raw register, (reg) → (uint32, error)
	check("mres_readback", chop>>24&0xF == 4)
	gconf, _ := m.ReadRegister(0x00)
	check("gconf_readback", gconf&0x4 == 0x4)
	m.Enable()                // Power stage on, () → error
	m.Move(50, 100)           // Move, (fullSteps, fullStepsPerS full steps/s) → error
	fault, _ := m.ReadFault() // Read fault bitmask, () → (uint8, error)
	check("no_fault", fault == 0)
	_, terr := m.ReadTstep() // Read step interval, () → (uint32, error)
	check("tstep_readable", terr == nil)
	m.SetStallThreshold(0)      // Set StallGuard2 threshold, (sgt -64..63) → error
	sg, _ := m.ReadStallguard() // Read StallGuard2 result, () → (uint16, error)
	check("sg_result_range", sg <= 1023)
	m.Disable() // Power stage off, () → error
	_, serr := m.ReadStatus()
	check("status_readable", serr == nil)
	_, gerr := m.ReadGlobalStatus()
	check("gstat_readable", gerr == nil)

	println("===DONE: " + strconv.Itoa(passed) + " passed, " + strconv.Itoa(failed) + " failed===")
}

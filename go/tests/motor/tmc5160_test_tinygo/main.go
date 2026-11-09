//go:build tinygo

// TMC5160 hardware test — TinyGo / Raspberry Pi Pico W smoke test.
//
// SPI0 on GP18 (SCK) / GP19 (MOSI) / GP16 (MISO) / GP17 (CS), Mode 3; DRV_ENN on GP10.
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
	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17, intPin, enPin) → *SPIConnection
	en := connection.NewGpioOutputPin(machine.GP10)                  // Create ENN pin, (machine.Pin) → *GpioOutputPin
	m, err := motor.NewTMC5160Full(conn, 0.075, en, nil, nil)        // Create TMC5160 Full driver, (connection, rSenseOhm=0.075 Ω, enPin, diag0, diag1) → (*TMC5160Full, error)
	check("init", err == nil)
	if err != nil {
		return
	}

	ioin, _ := m.ReadIoin() // Read IOIN, () → (TMC5160Ioin, error)
	check("ioin_version", ioin.Version == 0x30)
	check("verified_write", m.WriteRegister(0x13, 600, true) == nil) // Write a raw register, (reg, value, verify) → error
	m.SetMicrosteps(16)                                              // Set microstep resolution, (n 1–256) → error
	chop, _ := m.ReadRegister(0x6C)                                  // Read a raw register, (reg) → (uint32, error)
	check("mres_readback", chop>>24&0xF == 4)
	gconf, _ := m.ReadRegister(0x00)
	check("gconf_readback", gconf&0x4 == 0x4)
	m.SetMicrosteps(256)
	m.Enable()       // Power stage on, () → error
	m.SetPosition(0) // Define position, (position full steps) → error
	m.MoveTo(100)    // Start positioning move, (position full steps) → error
	check("move_reached", m.WaitUntilReached(0) == nil)
	pos, _ := m.Position() // Read XACTUAL, () → (float32 full steps, error)
	check("position_readback", pos > 99 && pos < 101)
	fault, _ := m.ReadFault() // Read fault bitmask, () → (uint8, error)
	check("no_fault", fault == 0)
	sg, _ := m.ReadStallguard() // Read StallGuard2 result, () → (uint16, error)
	check("sg_result_range", sg <= 1023)
	m.Disable() // Power stage off, () → error
	_, serr := m.ReadStatus()
	check("status_readable", serr == nil)
	_, gerr := m.ReadGlobalStatus()
	check("gstat_readable", gerr == nil)

	println("===DONE: " + strconv.Itoa(passed) + " passed, " + strconv.Itoa(failed) + " failed===")
}

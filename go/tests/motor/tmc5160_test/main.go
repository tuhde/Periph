//go:build linux && !tinygo

// TMC5160 hardware test — Linux host. Needs the SPI bus (Mode 3) and DRV_ENN GPIO wired.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, ok bool) {
	if ok {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Println("FAIL", label)
		failed++
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
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	defer conn.Close()
	en, _ := connection.NewGpioOutputPin(envInt("EN_LINE", 17))
	m, err := motor.NewTMC5160Full(conn, 0.075, en, nil, nil) // Create TMC5160 Full driver, (connection, rSenseOhm=0.075 Ω, enPin, diag0, diag1) → (*TMC5160Full, error)
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

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

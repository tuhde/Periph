//go:build linux && !tinygo

// TMC2208 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, cond bool) {
	if cond {
		fmt.Printf("PASS %s\n", label)
		passed++
	} else {
		fmt.Printf("FAIL %s\n", label)
		failed++
	}
}

func done() {
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func main() {
	port := "/dev/ttyS0"
	if p := os.Getenv("UART_PORT"); p != "" {
		port = p
	}
	baud := uint64(115200)
	if b := os.Getenv("UART_BAUD"); b != "" {
		v, err := strconv.ParseUint(b, 10, 32)
		if err != nil {
			fmt.Fprintln(os.Stderr, "UART_BAUD:", err)
			os.Exit(2)
		}
		baud = v
	}
	conn, err := connection.NewUARTConnection(port, uint32(baud), -1, nil, nil) // Create UART connection, (path, baudRate, dePinNum=-1, intPin, enPin) → (*UARTConnection, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "open uart:", err)
		os.Exit(2)
	}
	defer conn.Close()

	m, err := motor.NewTMC2208Full(conn, 0.11, nil, true, nil, nil, 12000000, false) // Create TMC2208 Full driver, (connection, rSenseOhm=0.11 Ω, enPin, echo=true, stepPin, dirPin, fclkHz, tmc2224) → (*TMC2208Full, error)
	check("init", err == nil)
	if err != nil {
		done()
		return
	}
	ioin, _ := m.ReadIoin() // Read IOIN, () → (TMC2208Ioin, error)
	check("ioin_version", ioin.Version != 0x00 && ioin.Version != 0xFF)
	check("ioin_sel_a", ioin.SelA)
	before, _ := m.ReadIfcnt() // Read write counter, () → (uint8, error)
	m.SetCurrent(0.5)          // Set run current, (rmsA A RMS) → error
	after, _ := m.ReadIfcnt()
	check("ifcnt_advances", after == before+2)
	m.SetMicrosteps(16)             // Set microstep resolution, (n 1–256) → error
	chop, _ := m.ReadRegister(0x6C) // Read a raw register, (reg) → (uint32, error)
	check("mres_readback", chop>>24&0xF == 4)
	gconf, _ := m.ReadRegister(0x00)
	check("gconf_pdn_disable", gconf&0x40 == 0x40)
	m.Enable() // Power stage on, () → error
	m.Run(100) // Spin with the internal generator, (stepsPerS) → error
	time.Sleep(time.Second)
	standstill, _ := m.IsStandstill() // Read standstill flag, () → (bool, error)
	check("moving", !standstill)
	tstep, _ := m.ReadTstep() // Read step interval, () → (uint32, error)
	check("tstep_nonzero", tstep > 0)
	fault, _ := m.ReadFault() // Read fault bitmask, () → (uint8, error)
	check("no_fault", fault == 0)
	m.Stop() // Stop the motor, () → error
	time.Sleep(500 * time.Millisecond)
	m.Disable()             // Power stage off, () → error
	st, _ := m.ReadStatus() // Read driver status, () → (TMC2208Status, error)
	check("status_decoded", st.CSActual <= 31)
	done()
}

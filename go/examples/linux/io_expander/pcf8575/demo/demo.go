//go:build linux && !tinygo

// PCF8575 demo example — Linux host.
//
// Button-controlled LED mirror on the 16-pin PCF8575. P00–P07 are
// LEDs (active-low); P10–P17 are push buttons. The loop reads the
// Port 1 byte, inverts it (pressed = 0 → LED on = 0), and writes
// the result to Port 0. Prints both port bytes and the decoded
// button/LED states so the quasi-bidirectional read-back and the
// 2-byte I²C protocol are visible.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x20"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := ioexpander.NewPCF8575Full(conn, uint8(addr)) // Create PCF8575 full driver, (connection, addr=0x20) → (*PCF8575Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure output/input bytes ---
	// P00–P07 as outputs (LEDs, active-low); P10–P17 as inputs
	// (buttons, internal pull-up). Writing 0xFF to both ports keeps
	// buttons in input mode and drives LEDs on.
	if err := chip.WritePort(0, 0xFF); err != nil { // Write Port 0, (port=0, mask=0xFF) → error
		panic(err)
	}
	if err := chip.WritePort(1, 0xFF); err != nil { // Write Port 1, (port=1, mask=0xFF) → error
		panic(err)
	}

	fmt.Println("Running — press buttons on P10–P17 to mirror to LEDs on P00–P07")

	for {
		port1, err := chip.ReadPort(1) // Read Port 1, (port=1) → (uint8, error)
		if err != nil {
			panic(err)
		}

		// --- Mirror button inputs to LED outputs ---
		// Buttons are in Port 1 (P10–P17); pressed = 0.
		// LEDs are active-low; invert: pressed (0) → LED on (bit = 0).
		ledBits := (^port1) & 0xFF
		if err := chip.WritePort(0, ledBits); err != nil { // Write Port 0, (port=0, mask) → error
			panic(err)
		}

		btnStr := ""
		for i := 0; i < 8; i++ {
			if (port1>>i)&1 == 0 {
				btnStr += "X"
			} else {
				btnStr += "."
			}
		}
		ledStr := ""
		for i := 0; i < 8; i++ {
			if (ledBits>>i)&1 == 1 {
				ledStr += "*"
			} else {
				ledStr += " "
			}
		}
		fmt.Printf("P0=0x%02X  P1=0x%02X  buttons=[%s]  LEDs=[%s]\n",
			ledBits, port1, btnStr, ledStr)

		time.Sleep(200 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

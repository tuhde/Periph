//go:build linux && !tinygo

// PCF8574 demo example — Linux host.
//
// Button-controlled LED mirror. P0–P3 are LEDs (active-low, cathode
// to pin, anode to VCC); P4–P7 are push buttons (pin to GND when
// pressed; internal pull-up = high when idle).
//
// The loop reads the button nibble (P4–P7) every 200 ms, inverts it
// (pressed = 0 → LED on = 0), and writes the result to the output
// nibble (P0–P3). Prints the raw port byte and decoded states so
// the quasi-bidirectional read-back is visible.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x20) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := ioexpander.NewPCF8574Full(tr, uint8(addr)) // Create PCF8574 full driver, (transport, addr=0x20) → (*PCF8574Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure output/input nibbles ---
	// P0–P3 as outputs (LEDs, active-low); P4–P7 as inputs (buttons,
	// internal pull-up). Writing 0xF0 keeps P4–P7 high (input mode)
	// and drives P0–P3 low (LEDs on).
	if err := chip.WritePort0(0xF0); err != nil { // Write all 8 pins, (mask=0xF0) → error
		panic(err)
	}

	fmt.Println("Running — press buttons on P4–P7 to mirror to LEDs on P0–P3")

	for {
		port, err := chip.ReadPort0() // Read all 8 pins, () → (uint8, error)
		if err != nil {
			panic(err)
		}

		// --- Mirror button inputs to LED outputs ---
		// Buttons are in bits 4–7; pressed = 0 (pulled to GND by
		// button). LEDs are active-low; invert: pressed (0) → LED
		// on (bit = 0 in output).
		buttons := (port >> 4) & 0x0F
		ledBits := (^buttons) & 0x0F
		if err := chip.WritePort0(0xF0 | ledBits); err != nil { // Write all 8 pins, (mask) → error
			panic(err)
		}

		btnStr := ""
		for i := 0; i < 4; i++ {
			if (buttons>>i)&1 == 0 {
				btnStr += "X"
			} else {
				btnStr += "."
			}
		}
		ledStr := ""
		for i := 0; i < 4; i++ {
			if (ledBits>>i)&1 == 1 {
				ledStr += "*"
			} else {
				ledStr += " "
			}
		}
		fmt.Printf("port=0x%02X  buttons=[%s]  LEDs=[%s]\n", port, btnStr, ledStr)

		time.Sleep(200 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

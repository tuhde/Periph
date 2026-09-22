//go:build tinygo

// TPIC6B595 demo example — TinyGo / Raspberry Pi Pico W.
//
// "Knight rider" chase pattern across two cascaded devices, with
// periodic global blanking via G to demonstrate glitch-free dimming.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	numDevices = 2
	numOutputs = numDevices * 8
)

func main() {
	serIn := machine.GP19
	srck  := machine.GP26
	rck   := machine.GP17
	srclr := machine.GP16
	g     := machine.GP15
	serIn.Configure(machine.PinConfig{Mode: machine.PinOutput})
	srck.Configure(machine.PinConfig{Mode: machine.PinOutput})
	rck.Configure(machine.PinConfig{Mode: machine.PinOutput})
	srclr.Configure(machine.PinConfig{Mode: machine.PinOutput})
	g.Configure(machine.PinConfig{Mode: machine.PinOutput})

	conn := connection.NewSIPOSoftwareSPI(serIn, srck, rck, srclr, g, nil) // Create SiPo connection, (serIn, srck, rck, srclr, g, enPin=nil) → *SIPOConnection
	chip, err := ioexpander.NewTPIC6B595Full(conn, numDevices)             // Create TPIC6B595 full driver, (connection, numDevices=2) → (*TPIC6B595Full, error)
	if err != nil {
		panic(err)
	}
	// two cascaded devices — 16 outputs total; outputs start OFF

	position := 0
	direction := 1
	sweepCount := 0
	const (
		blankEvery = 3
		blankMS    = 500
	)

	for {
		// --- Walk a single lit LED across all 16 outputs and back ---
		// Use write_all() each step so both cascaded devices latch together —
		// there is no way to update just one downstream device without re-sending
		// the whole chain's data.
		bytes := []uint8{0, 0}
		port := position / 8
		bit := position % 8
		bytes[port] = 1 << bit
		if err := chip.WriteAll(bytes); err != nil { // Write all device bytes, (values=[0x01, 0x80]) → error
			panic(err)
		}
		fmt.Printf("position=%d  bytes=[0x%02X, 0x%02X]\n", position, bytes[0], bytes[1])

		// --- Periodically blank every output via G, then resume ---
		// set_output_enable(false) drives G HIGH, forcing every DMOS off without
		// touching the shadow register — the LEDs simply resume exactly where they
		// left off when G is re-enabled.
		sweepCount++
		if sweepCount%blankEvery == 0 {
			if err := chip.SetOutputEnable(false); err != nil { // Force every output off via G, (enabled=false) → error
				panic(err)
			}
			// the chase pattern's shadow state is preserved
			fmt.Printf("  blanked via G for %d ms\n", blankMS)
			time.Sleep(blankMS * time.Millisecond)
			if err := chip.SetOutputEnable(true); err != nil { // Re-enable outputs, (enabled=true) → error
				panic(err)
			}
			// LEDs resume from the previously-latched state
		}

		// Bounce the chase position at both ends of the strip
		position += direction
		if position >= numOutputs-1 || position <= 0 {
			direction = -direction
			time.Sleep(100 * time.Millisecond)
		} else {
			time.Sleep(80 * time.Millisecond)
		}
	}
}

//go:build tinygo

// TPIC6B595 minimal example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	serIn := machine.GP19
	srck  := machine.GP26
	rck   := machine.GP17
	serIn.Configure(machine.PinConfig{Mode: machine.PinOutput})
	srck.Configure(machine.PinConfig{Mode: machine.PinOutput})
	rck.Configure(machine.PinConfig{Mode: machine.PinOutput})

	conn := connection.NewSIPOSoftwareSPI(serIn, srck, rck, 0, 0, nil) // Create SiPo connection, (serIn, srck, rck, srclr=0, g=0, enPin=nil) → *SIPOConnection
	chip, err := ioexpander.NewTPIC6B595Minimal(conn, 1)               // Create TPIC6B595 driver, (connection, numDevices=1) → (*TPIC6B595Minimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get pin proxy, (n=0) → TPIC6B595Pin
	p7 := chip.Pin(7) // Get pin proxy, (n=7) → TPIC6B595Pin

	for {
		if err := p0.Set(true); err != nil { // Set DMOS output ON, (high=true) → error
			panic(err)
		}
		if err := p7.Set(false); err != nil { // Set DMOS output OFF, (high=false) → error
			panic(err)
		}
		time.Sleep(500 * time.Millisecond)
		if err := p0.Set(false); err != nil { // Set DMOS output OFF, (high=false) → error
			panic(err)
		}
		if err := p7.Set(true); err != nil { // Set DMOS output ON, (high=true) → error
			panic(err)
		}
		time.Sleep(500 * time.Millisecond)
	}
}

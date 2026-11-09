//go:build tinygo

// TMC5160 minimal example — TinyGo / Raspberry Pi Pico W. Positioning moves with the internal ramp generator.
//
// SPI0 on GP18 (SCK) / GP19 (MOSI) / GP16 (MISO) / GP17 (CS), Mode 3; DRV_ENN on GP10, DIAG0 on GP13.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = time.Second

func run(conn connection.Connection, en connection.OutputPin) {
	m, err := motor.NewTMC5160Minimal(conn, 0.075, en) // Create TMC5160 driver, (connection, rSenseOhm=0.075 Ω, enPin) → (*TMC5160Minimal, error)
	check(err)

	check(m.SetCurrent(1.0)) // Set run current, (rmsA A RMS) → error
	check(m.Enable())        // Power stage on, () → error
	for _, target := range []float32{400, 0} {
		check(m.MoveTo(target))      // Start positioning move, (position full steps) → error
		check(m.WaitUntilReached(0)) // Wait for the ramp generator, (timeoutS=0 s, 0 = estimate) → error
		pos, err := m.Position()     // Read XACTUAL, () → (float32 full steps, error)
		check(err)
		fault, err := m.ReadFault() // Read fault bitmask, () → (uint8, error)
		check(err)
		fmt.Printf("position=%v fault=%d\n", pos, fault)
	}
	check(m.Disable()) // Power stage off, () → error
}

func check(err error) {
	if err != nil {
		println("error:", err.Error())
		for {
			time.Sleep(time.Second)
		}
	}
}

func main() {
	spi := machine.SPI0
	check(spi.Configure(machine.SPIConfig{Frequency: 1_000_000, SCK: machine.GP18, SDO: machine.GP19, SDI: machine.GP16, Mode: 3}))
	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17, intPin, enPin) → *SPIConnection
	en := connection.NewGpioOutputPin(machine.GP10)                  // Create DRV_ENN pin, (machine.Pin) → *GpioOutputPin
	run(conn, en)
}

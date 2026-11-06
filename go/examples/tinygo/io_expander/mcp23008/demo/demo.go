//go:build tinygo

// MCP23008 demo example — TinyGo / Raspberry Pi Pico W. LED scanner on GP0–GP3 with interrupt-driven buttons on GP4–GP7.
package main

import (
	"fmt"
	"machine"
	"sync/atomic"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}
	devAddr := uint8(0x20)

	conn := connection.NewI2CConnection(i2c, devAddr, nil, nil) // Create I2C connection, (i2c, addr=0x20, intPin=nil, enPin=nil) → *I2CConnection

	chip, err := ioexpander.NewMCP23008Full(conn, devAddr) // Create MCP23008 driver, (connection, addr=0x20) → (*MCP23008Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure LEDs and buttons ---
	// GP0–GP3 are LED outputs; GP4–GP7 stay inputs with the 100 kΩ pull-ups on,
	// so a pressed button (to GND) reads 0.
	for n := uint8(0); n < 4; n++ {
		_ = chip.Pin(n).Mode(ioexpander.MCP23008Output) // Make LED pin an output, (m PinMode) → error
	}
	_ = chip.ConfigurePullup(0xF0) // Pull-ups on GP4–GP7, (mask) → error

	// --- Arm interrupt-on-change on the buttons ---
	// Any button edge raises INT (or the 5 ms poll sees it); the callback prints INTF and
	// the INTCAP snapshot and holds the LED matching the pressed button for one second.
	var holdMask atomic.Uint32
	var holdUntil atomic.Int64
	_ = chip.EnablePinInterrupt(0xF0) // Arm GP4–GP7, (mask) → error
	_ = chip.OnInterrupt(func(status uint8) {
		captured, _ := chip.ReadCapture()
		fmt.Printf("button INTF=0x%02X INTCAP=0x%02X\n", status, captured)
		holdMask.Store(uint32(status>>4) & 0x0F)
		holdUntil.Store(time.Now().Add(time.Second).UnixNano())
	}, ioexpander.IntOnChange) // Subscribe to INT, (callback, mode IntMode) → error

	// --- Scanner loop ---
	for step := 0; step < 40; step++ {
		leds := uint8(1 << (step % 4))
		if time.Now().UnixNano() < holdUntil.Load() {
			leds = uint8(holdMask.Load())
		}
		_ = chip.WritePort(0, leds) // Write LED outputs, (port=0, mask) → error
		port, _ := chip.ReadPort(0) // Read all 8 pins, (port=0) → (uint8, error)
		fmt.Printf("  pos=%d  port=0x%02X\n", step%4, port)
		time.Sleep(100 * time.Millisecond)
	}

	// --- Shut down ---
	_ = chip.OffInterrupt()     // Unsubscribe, () → error
	_ = chip.WritePort(0, 0x00) // Clear all outputs, (port=0, mask=0x00) → error
}

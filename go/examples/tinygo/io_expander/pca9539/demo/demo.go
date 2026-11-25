//go:build tinygo

// PCA9539 demo example — TinyGo / Raspberry Pi Pico W. LED scanner on P00–P07 with interrupt-driven buttons on P10–P17.
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
	devAddr := uint8(0x74)

	conn := connection.NewI2CConnection(i2c, devAddr, nil, nil) // Create I2C connection, (i2c, addr=0x74, intPin=nil, enPin=nil) → *I2CConnection

	chip, err := ioexpander.NewPCA9539Full(conn, devAddr) // Create PCA9539 driver, (connection, addr=0x74) → (*PCA9539Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure LEDs and buttons ---
	// Port 0 holds the active-low LED outputs (0 lights the LED); port 1 stays input and relies on
	// external pull-ups (the chip has none), so a pressed button (to GND) reads 0. Polarity
	// inversion makes a pressed button read as 1.
	_ = chip.WriteAll(0xFFFF)          // Turn all LEDs off, (value=0xFFFF) → error
	_ = chip.SetDirection(0xFF00)      // Port 0 outputs, port 1 inputs, (mask) → error
	_ = chip.ConfigurePolarity(0xFF00) // Invert port 1 inputs, (mask) → error

	// --- Subscribe to input changes ---
	// Any button edge raises INT (or the 5 ms poll sees it); the callback prints the 16-bit changed
	// mask and the pin state and holds the LED matching the pressed button for one second.
	var holdMask atomic.Uint32
	var holdUntil atomic.Int64
	holdMask.Store(0xFF)
	_ = chip.OnInterrupt(func(changed uint16) { // Subscribe to INT, (callback func(changed uint16)) → error
		all, _ := chip.ReadAll()
		fmt.Printf("button changed=0x%04X all=0x%04X\n", changed, all)
		holdMask.Store(uint32(^(changed >> 8)) & 0xFF)
		holdUntil.Store(time.Now().Add(time.Second).UnixNano())
	})

	// --- Scanner loop ---
	for step := 0; step < 80; step++ {
		leds := uint8(^(1 << (step % 8)))
		if time.Now().UnixNano() < holdUntil.Load() {
			leds = uint8(holdMask.Load())
		}
		_ = chip.WriteAll(0xFF00 | uint16(leds)) // Write LED outputs, (value uint16) → error
		all, _ := chip.ReadAll()                 // Read all 16 pins, () → (uint16, error)
		fmt.Printf("  pos=%d  all=0x%04X\n", step%8, all)
		time.Sleep(100 * time.Millisecond)
	}

	// --- Shut down ---
	_ = chip.OffInterrupt()   // Unsubscribe, () → error
	_ = chip.WriteAll(0xFFFF) // Turn all LEDs off, (value=0xFFFF) → error
}

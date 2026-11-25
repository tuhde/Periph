//go:build linux && !tinygo

// PCA6416A demo example — Linux host. LED scanner on P0_0–P0_7 with interrupt-driven buttons on P1_0–P1_7.
package main

import (
	"fmt"
	"os"
	"strconv"
	"sync/atomic"
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
	devAddr := uint8(addr)

	conn, err := connection.NewI2CConnection(bus, devAddr, nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := ioexpander.NewPCA6416AFull(conn, devAddr) // Create PCA6416A driver, (connection, addr=0x20) → (*PCA6416AFull, error)
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

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

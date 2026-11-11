//go:build linux && !tinygo

// PCA9536 demo example — Linux host. blinking LEDs on P0–P1 with polled buttons on P2–P3.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x41"), 0, 8)
	if err != nil {
		panic(err)
	}
	devAddr := uint8(addr)

	conn, err := connection.NewI2CConnection(bus, devAddr, nil, nil) // Create I2C connection, (bus=1, addr=0x41, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := ioexpander.NewPCA9536Full(conn, devAddr) // Create PCA9536 driver, (connection, addr=0x41) → (*PCA9536Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure LEDs and buttons ---
	// P0–P1 are active-low LED outputs (0 lights the LED); P2–P3 stay inputs and rely on the
	// built-in weak pull-up, so a pressed button (to GND) reads 0.
	_ = chip.WritePort(0, 0x0F) // Turn all LEDs off, (port=0, mask=0x0F) → error
	_ = chip.SetDirection(0x0C) // P0–P1 outputs, P2–P3 inputs, (mask) → error

	// --- Watch for input changes ---
	// The chip has no INT pin: a 5 ms polling goroutine reports changes; the callback prints
	// the changed mask and the port state and holds the LED matching the pressed button for
	// one second.
	var holdMask atomic.Uint32
	var holdUntil atomic.Int64
	holdMask.Store(0x03)
	_ = chip.WatchChanges(func(changed uint8) { // Start polling for changes, (callback func(changed uint8), interval) → error
		port, _ := chip.ReadPort(0)
		fmt.Printf("button changed=0x%X port=0x%X\n", changed, port)
		holdMask.Store(uint32(^(changed >> 2)) & 0x03)
		holdUntil.Store(time.Now().Add(time.Second).UnixNano())
	}, 5*time.Millisecond)

	// --- Blink loop ---
	for step := 0; step < 40; step++ {
		leds := uint8(0x01)
		if step%2 == 0 {
			leds = 0x02
		}
		if time.Now().UnixNano() < holdUntil.Load() {
			leds = uint8(holdMask.Load())
		}
		_ = chip.WritePort(0, 0x0C|leds) // Write LED outputs, (port=0, mask) → error
		port, _ := chip.ReadPort(0)      // Read all 4 pins, (port=0) → (uint8, error)
		fmt.Printf("  step=%d  port=0x%X\n", step, port)
		time.Sleep(100 * time.Millisecond)
	}

	// --- Shut down ---
	chip.UnwatchChanges()       // Stop polling, ()
	_ = chip.WritePort(0, 0x0F) // Turn all LEDs off, (port=0, mask=0x0F) → error
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

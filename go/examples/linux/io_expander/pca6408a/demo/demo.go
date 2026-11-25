//go:build linux && !tinygo

// PCA6408A demo example — Linux host. LED scanner on P0–P3 with interrupt-driven buttons on P4–P7.
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

	chip, err := ioexpander.NewPCA6408AFull(conn, devAddr) // Create PCA6408A driver, (connection, addr=0x20) → (*PCA6408AFull, error)
	if err != nil {
		panic(err)
	}

	// --- Configure LEDs and buttons ---
	// P0–P3 are active-low LED outputs (0 lights the LED); P4–P7 stay inputs and rely on
	// external pull-ups (the chip has none), so a pressed button (to GND) reads 0.
	_ = chip.WritePort(0, 0xFF) // Turn all LEDs off, (port=0, mask=0xFF) → error
	_ = chip.SetDirection(0xF0) // P0–P3 outputs, P4–P7 inputs, (mask) → error

	// --- Subscribe to input changes ---
	// Any button edge raises INT (or the 5 ms poll sees it); the callback prints the changed
	// mask and the port state and holds the LED matching the pressed button for one second.
	var holdMask atomic.Uint32
	var holdUntil atomic.Int64
	holdMask.Store(0x0F)
	_ = chip.OnInterrupt(func(changed uint8) { // Subscribe to INT, (callback func(changed uint8)) → error
		port, _ := chip.ReadPort(0)
		fmt.Printf("button changed=0x%02X port=0x%02X\n", changed, port)
		holdMask.Store(uint32(^(changed >> 4)) & 0x0F)
		holdUntil.Store(time.Now().Add(time.Second).UnixNano())
	})

	// --- Scanner loop ---
	for step := 0; step < 40; step++ {
		leds := uint8(^(1 << (step % 4))) & 0x0F
		if time.Now().UnixNano() < holdUntil.Load() {
			leds = uint8(holdMask.Load())
		}
		_ = chip.WritePort(0, 0xF0|leds) // Write LED outputs, (port=0, mask) → error
		port, _ := chip.ReadPort(0)      // Read all 8 pins, (port=0) → (uint8, error)
		fmt.Printf("  pos=%d  port=0x%02X\n", step%4, port)
		time.Sleep(100 * time.Millisecond)
	}

	// --- Shut down ---
	_ = chip.OffInterrupt()     // Unsubscribe, () → error
	_ = chip.WritePort(0, 0xFF) // Turn all LEDs off, (port=0, mask=0xFF) → error
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

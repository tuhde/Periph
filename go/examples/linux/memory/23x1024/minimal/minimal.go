//go:build linux && !tinygo

// 23X1024 minimal example — Linux host.
//
// Writes and reads back an incrementing counter at address 0 twice per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewSPIConnection(bus, device, 0, 8_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, max_speed=8 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := memory.NewSRAM23X1024Minimal(conn) // Create 23X1024 driver, (connection) → (*SRAM23X1024Minimal, error)
	if err != nil {
		panic(err)
	}

	for counter := byte(0); ; counter++ {
		if err := chip.WriteByteAt(0x00000, counter); err != nil { // Write one byte, (address 0–131071, value 0–255) → error
			fmt.Fprintln(os.Stderr, "sram:", err)
			continue
		}
		value, err := chip.ReadByteAt(0x00000) // Read one byte, (address 0–131071) → (byte, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "sram:", err)
			continue
		}
		fmt.Printf("wrote 0x%02X, read 0x%02X\n", counter, value)
		time.Sleep(500 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

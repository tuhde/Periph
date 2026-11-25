//go:build linux && !tinygo

// 23X1024 complete example — Linux host.
//
// Exercises every method of SRAM23X1024Full.
package main

import (
	"bytes"
	"fmt"
	"os"
	"strconv"

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

	chip, err := memory.NewSRAM23X1024Full(conn) // Create 23X1024 Full driver, (connection) → (*SRAM23X1024Full, error)
	// writes MODE=0x40 (Sequential) and verifies it with RDMR
	if err != nil {
		panic(err)
	}

	fmt.Printf("size: %d bytes\n", chip.Size())
	// Array size, () → uint32 bytes — always 131072

	if err := chip.WriteByteAt(0x00010, 0xA5); err != nil { // Write one byte, (address 0–131071, value 0–255) → error
		panic(err) // WRITE 0x02 + 24-bit address + data
	}
	b, _ := chip.ReadByteAt(0x00010) // Read one byte, (address 0–131071) → (byte, error)
	// READ 0x03 + 24-bit address
	fmt.Printf("byte @0x10: 0x%02X\n", b)

	pattern := make([]byte, 300)
	for i := range pattern {
		pattern[i] = byte(i)
	}
	if err := chip.Write(0x00100, pattern); err != nil { // Block write, (address, data) → error
		panic(err) // split into transactions of at most MaxChunk bytes
	}
	readback, _ := chip.Read(0x00100, len(pattern)) // Block read, (address, length) → ([]byte, error)
	// sequential read across the chunk boundary
	fmt.Println("block of 300:", matchStr(bytes.Equal(readback, pattern)))

	if err := chip.Fill(0x00200, 64, 0xFF); err != nil { // Fill a range, (address, length, value) → error
		panic(err) // writes value over the range, chunked
	}
	f, _ := chip.ReadByteAt(0x00200)
	fmt.Printf("fill @0x200: 0x%02X\n", f)

	chip.SetMaxChunk(64) // Limit bytes per transaction, (n 1–256) → error
	// smaller frames for bus layers with small buffers
	fmt.Println("max_chunk:", chip.MaxChunk())
	chip.SetMaxChunk(256) // Limit bytes per transaction, (n 1–256) → error

	if err := chip.SetMode(memory.SRAM23X1024ModePage); err != nil { // Select operating mode, (mode=ModePage=0x80) → error
		panic(err) // WRMR then RDMR check; accesses now wrap within 32-byte pages
	}
	mode, _ := chip.GetMode() // Read operating mode, () → (byte, error)
	// RDMR bits 7:6
	fmt.Printf("mode: 0x%02X\n", mode)
	if err := chip.Write(0x00110, pattern[:64]); err != nil { // Block write, (address, data) → error
		panic(err) // the driver splits at page boundaries, so the data stays linear
	}
	paged, _ := chip.Read(0x00110, 64) // Block read, (address, length) → ([]byte, error)
	fmt.Println("page mode 64 bytes:", matchStr(bytes.Equal(paged, pattern[:64])))

	chip.SetMode(memory.SRAM23X1024ModeSequential) // Select operating mode, (mode=ModeSequential=0x40) → error
	chip.ResetIO()                                 // Return to SPI mode, () → error
	// sends RSTIO (0xFF); harmless while already in SPI mode
}

func matchStr(ok bool) string {
	if ok {
		return "match"
	}
	return "MISMATCH"
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

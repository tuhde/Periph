//go:build tinygo

// 23X1024 complete example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.SPI0 (GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS, SPI Mode 0).
// Exercises every method of SRAM23X1024Full.
package main

import (
	"bytes"
	"machine"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 8_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)

	chip, err := memory.NewSRAM23X1024Full(conn) // Create 23X1024 Full driver, (connection) → (*SRAM23X1024Full, error)
	// writes MODE=0x40 (Sequential) and verifies it with RDMR
	if err != nil {
		panic(err)
	}

	println("size:", chip.Size())
	// Array size, () → uint32 bytes — always 131072

	if err := chip.WriteByteAt(0x00010, 0xA5); err != nil { // Write one byte, (address 0–131071, value 0–255) → error
		panic(err) // WRITE 0x02 + 24-bit address + data
	}
	b, _ := chip.ReadByteAt(0x00010) // Read one byte, (address 0–131071) → (byte, error)
	// READ 0x03 + 24-bit address
	println("byte @0x10:", strconv.FormatInt(int64(b), 16))

	pattern := make([]byte, 300)
	for i := range pattern {
		pattern[i] = byte(i)
	}
	if err := chip.Write(0x00100, pattern); err != nil { // Block write, (address, data) → error
		panic(err) // split into transactions of at most MaxChunk bytes
	}
	readback, _ := chip.Read(0x00100, len(pattern)) // Block read, (address, length) → ([]byte, error)
	// sequential read across the chunk boundary
	println("block of 300:", matchStr(bytes.Equal(readback, pattern)))

	if err := chip.Fill(0x00200, 64, 0xFF); err != nil { // Fill a range, (address, length, value) → error
		panic(err) // writes value over the range, chunked
	}
	f, _ := chip.ReadByteAt(0x00200)
	println("fill @0x200:", strconv.FormatInt(int64(f), 16))

	chip.SetMaxChunk(64) // Limit bytes per transaction, (n 1–256) → error
	// smaller frames for bus layers with small buffers
	println("max_chunk:", chip.MaxChunk())
	chip.SetMaxChunk(256) // Limit bytes per transaction, (n 1–256) → error

	if err := chip.SetMode(memory.SRAM23X1024ModePage); err != nil { // Select operating mode, (mode=ModePage=0x80) → error
		panic(err) // WRMR then RDMR check; accesses now wrap within 32-byte pages
	}
	mode, _ := chip.GetMode() // Read operating mode, () → (byte, error)
	// RDMR bits 7:6
	println("mode:", strconv.FormatInt(int64(mode), 16))
	if err := chip.Write(0x00110, pattern[:64]); err != nil { // Block write, (address, data) → error
		panic(err) // the driver splits at page boundaries, so the data stays linear
	}
	paged, _ := chip.Read(0x00110, 64) // Block read, (address, length) → ([]byte, error)
	println("page mode 64 bytes:", matchStr(bytes.Equal(paged, pattern[:64])))

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

//go:build tinygo

// 23X1024 demo — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.SPI0 (GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS, SPI Mode 0).
// Scratch-RAM demo: lookup table, full-array fill, page-crossing write and throughput.
package main

import (
	"bytes"
	"machine"
	"math"
	"strconv"
	"time"

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
	if err != nil {
		panic(err)
	}

	// --- Store a 256-entry lookup table in external SRAM ---
	// A sine table is the kind of data a microcontroller with little RAM
	// keeps outside: written once, then read back entry by entry.
	table := make([]byte, 256)
	for i := range table {
		table[i] = byte(127.5 + 127.5*math.Sin(2*math.Pi*float64(i)/256))
	}
	if err := chip.Write(0x00000, table); err != nil { // Block write, (address, data) → error
		panic(err)
	}
	check, _ := chip.Read(0x00000, len(table)) // Block read, (address, length) → ([]byte, error)
	peak, _ := chip.ReadByte(64)               // Read one byte, (address) → (byte, error)
	println("sine table:", okStr(bytes.Equal(check, table)), "sin(64/256 turn) =", peak)

	// --- Wipe the whole array and spot-check it ---
	// Zero write time means the 128 KiB fill runs at SPI speed with no
	// waiting; the first, middle and last byte prove the full range.
	if err := chip.Fill(0x00000, int(chip.Size()), 0xA5); err != nil { // Fill a range, (address, length, value) → error
		panic(err)
	}
	first, _ := chip.ReadByte(0x00000) // Read one byte, (address) → (byte, error)
	mid, _ := chip.ReadByte(0x10000)
	last, _ := chip.ReadByte(0x1FFFF)
	println("fill spot check:", strconv.FormatInt(int64(first), 16), strconv.FormatInt(int64(mid), 16), strconv.FormatInt(int64(last), 16))

	// --- Page mode: a write that crosses a 32-byte page boundary ---
	// The hardware would wrap inside the page; the driver splits the
	// transfer so the 64 bytes starting at address 16 still read back in order.
	if err := chip.SetMode(memory.SRAM23X1024ModePage); err != nil { // Select operating mode, (mode=ModePage=0x80) → error
		panic(err)
	}
	ramp := make([]byte, 64)
	for i := range ramp {
		ramp[i] = byte(0x40 + i)
	}
	if err := chip.Write(16, ramp); err != nil { // Block write, (address, data) → error
		panic(err)
	}
	rampBack, _ := chip.Read(16, len(ramp)) // Block read, (address, length) → ([]byte, error)
	println("page-crossing write:", okStr(bytes.Equal(rampBack, ramp)))
	chip.SetMode(memory.SRAM23X1024ModeSequential) // Select operating mode, (mode=ModeSequential=0x40) → error

	// --- Measure throughput for a 4 KiB transfer ---
	// Sequential mode streams the block in 256-byte transactions;
	// time.Now() times them.
	block := make([]byte, 4096)
	for i := range block {
		block[i] = byte(i)
	}
	t0 := time.Now()
	if err := chip.Write(0x01000, block); err != nil { // Block write, (address, data) → error
		panic(err)
	}
	t1 := time.Now()
	chip.Read(0x01000, len(block)) // Block read, (address, length) → ([]byte, error)
	t2 := time.Now()
	println("4 KiB write ms:", t1.Sub(t0).Milliseconds(), "read ms:", t2.Sub(t1).Milliseconds())
}

func okStr(ok bool) string {
	if ok {
		return "ok"
	}
	return "MISMATCH"
}

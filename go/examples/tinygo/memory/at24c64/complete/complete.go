//go:build tinygo

// AT24C64 complete example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the Full driver and calls every public method once.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnectionWithWidth(i2c, 0x50, 2, nil, nil) // Create I2C connection, (i2c, addr=0x50, regBytes=2) → (*I2CConnection)

	chip, err := memory.NewAT24C64Full(conn) // Create AT24C64 driver, (connection) → (*AT24C64Full, error)
	chk(err)

	fmt.Println("Size:", chip.Size(), "bytes") // Get array size, () → int
	// always 8192
	fmt.Println("Page:", chip.PageSize(), "bytes") // Get write page size, () → int
	// always 32
	chk(chip.SetMaxChunk(16))                 // Set max bytes per bus transaction, (n=30) → error
	fmt.Println("MaxChunk:", chip.MaxChunk()) // Get max bytes per bus transaction, () → int

	chk(chip.WriteByteAt(0x0010, 0xA5)) // Write a single byte, (address 0–8191, value 0–255) → error
	// ACK-polled until the write cycle ends
	b, err := chip.ReadByteAt(0x0010) // Read a single byte, (address 0–8191) → (byte, error)
	// random read
	chk(err)
	fmt.Printf("Byte: 0x%02X\n", b)

	block := make([]byte, 40)
	for i := range block {
		block[i] = byte(i)
	}
	chk(chip.Write(0x0100, block)) // Write data, (address, data) → error
	// split at 32-byte page boundaries and MaxChunk, ACK-polled per chunk
	data, err := chip.Read(0x0100, 40) // Sequential read, (address, length) → ([]byte, error)
	// split into MaxChunk-byte transactions
	chk(err)
	fmt.Printf("Block: %x\n", data)

	chk(chip.WritePage(0x0180, block[:16])) // Write within one page, (address, data) → error
	// ErrAT24C64PageBoundary if the data crosses a page boundary
	chk(chip.Fill(0x0100, 40, 0xFF)) // Fill a range, (address, length, value) → error
	// page-wise writes of value
	fmt.Println("Ready:", chip.WaitReady(10*time.Millisecond)) // ACK-poll the chip, (timeout) → bool
	// true once the chip answers its address

	for {
		time.Sleep(time.Second)
	}
}

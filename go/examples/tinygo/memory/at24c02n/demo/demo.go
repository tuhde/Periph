//go:build tinygo

// AT24C02N demo example — TinyGo / Raspberry Pi Pico W.
//
// Persistent boot counter, page-write benchmark and page-boundary rules.
package main

import (
	"bytes"
	"encoding/binary"
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

	conn := connection.NewI2CConnection(i2c, 0x50, nil, nil) // Create I2C connection, (i2c, addr=0x50) → (*I2CConnection)

	chip, err := memory.NewAT24C02NFull(conn) // Create AT24C02N driver, (connection) → (*AT24C02NFull, error)
	chk(err)

	// --- Persistent boot counter: 4-byte big-endian value at address 0 ---
	cb, err := chip.Read(0x0000, 4) // Sequential read, (address, length) → ([]byte, error)
	chk(err)
	counter := binary.BigEndian.Uint32(cb) + 1
	binary.BigEndian.PutUint32(cb, counter)
	chk(chip.Write(0x0000, cb)) // Write data, (address, data) → error
	fmt.Println("Boot counter:", counter)

	// --- Page-write benchmark: 3 pages, 8-byte aligned ---
	size := 3 * chip.PageSize() // Get write page size, () → int
	pattern := make([]byte, size)
	for i := range pattern {
		pattern[i] = byte(i * 7)
	}
	start := time.Now()
	chk(chip.Write(0x0080, pattern)) // Write data, (address, data) → error
	elapsed := time.Since(start)
	fmt.Printf("Wrote %d bytes in %.1f ms (%.0f B/s incl. ACK-polled write cycles)\n", size,
		float64(elapsed)/float64(time.Millisecond), float64(size)/elapsed.Seconds())
	back, err := chip.Read(0x0080, size) // Sequential read, (address, length) → ([]byte, error)
	chk(err)
	fmt.Println("Read-back matches:", bytes.Equal(back, pattern))

	// --- A write that crosses a page boundary is refused ---
	fmt.Println("WritePage across boundary:", chip.WritePage(0x00F4, make([]byte, 8))) // Write within one page, (address, data) → error

	chk(chip.Fill(0x0080, size, 0))                                                // Fill a range, (address, length, value) → error
	fmt.Println("Size", chip.Size(), "ready", chip.WaitReady(10*time.Millisecond)) // Get array size, () → int ; ACK-poll the chip, (timeout) → bool

	for {
		time.Sleep(time.Second)
	}
}

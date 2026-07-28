//go:build tinygo

// 24AA02UID demo example — TinyGo / Raspberry Pi Pico W.
//
// Device tracking: read the factory UID, maintain a 4-byte boot counter
// in user EEPROM, and loop reading the UID to show it never changes
// while the counter does.
package main

import (
	"encoding/binary"
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
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

	conn := connection.NewI2CConnection(i2c, 0x50, nil, nil) // Create I2C connection, (i2c, addr=0x50) → (*I2CConnection)

	chip, err := memory.NewEEPROM24AA02UIDFull(conn) // Create 24AA02UID driver, (connection) → (*EEPROM24AA02UIDFull, error)
	if err != nil {
		panic(err)
	}

	// --- Read the chip's factory-programmed 32-bit serial number ---
	// The UID at 0xFC-0xFF never changes and identifies the device.
	uid, err := chip.ReadUID() // Read 32-bit unique serial number, () → ([4]byte, error)
	if err != nil {
		panic(err)
	}
	// reads 4 bytes at 0xFC-0xFF
	fmt.Printf("Device UID: %02X%02X%02X%02X\n", uid[0], uid[1], uid[2], uid[3])
	uidInt := binary.BigEndian.Uint32(uid[:])
	fmt.Printf("Device UID int: %d\n", uidInt)

	// --- Maintain a 4-byte boot counter in user EEPROM at 0x00-0x03 ---
	// Read the existing value (or zero on a fresh chip), increment,
	// write back as 4 big-endian bytes. The user EEPROM is rewritable;
	// the UID region above 0x80 is not, so the two stay independent.
	existing, err := chip.Read(0x00, 4) // Sequential read, (address, length) → ([]byte, error)
	if err != nil {
		panic(err)
	}
	// reads 4 bytes from user EEPROM
	var counter uint32
	if len(existing) == 4 {
		counter = binary.BigEndian.Uint32(existing)
	}
	counter++
	var buf [4]byte
	binary.BigEndian.PutUint32(buf[:], counter)
	if err := chip.Write(0x00, buf[:]); err != nil { // Arbitrary-length write, (address, data) → error
		panic(err)
	}
	// writes 4 bytes; waits for each chunk
	fmt.Printf("Boot count: %d\n", counter)

	for n := 0; n < 5; n++ {
		// --- Loop reading the UID only, showing it never changes ---
		// The two distinct areas of the chip (immutable identification
		// above 0x80, rewritable storage below 0x80) are exercised
		// independently.
		uid, err := chip.ReadUID() // Read 32-bit unique serial number, () → ([4]byte, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("[%d] UID: %02X%02X%02X%02X  (counter at user EEPROM 0x00-0x03)\n", n, uid[0], uid[1], uid[2], uid[3])
		time.Sleep(2 * time.Second)
	}
}

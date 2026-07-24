//go:build linux && !tinygo

// 24AA02UID demo example — Linux host.
//
// Device tracking: read the factory UID, maintain a 4-byte boot counter
// in user EEPROM, and loop reading the UID to show it never changes
// while the counter does.
package main

import (
	"encoding/binary"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x50"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x50) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := memory.NewEEPROM24AA02UIDFull(tr) // Create 24AA02UID driver, (transport) → (*EEPROM24AA02UIDFull, error)
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

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

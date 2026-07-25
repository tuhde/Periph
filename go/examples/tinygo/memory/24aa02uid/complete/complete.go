//go:build tinygo

// 24AA02UID complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the EEPROM24AA02UIDFull API on bare metal.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x50)                 // Create I2C transport, (i2c, addr=0x50) → (*I2CTransport)
	chip, err := memory.NewEEPROM24AA02UIDFull(tr)            // Create 24AA02UID driver, (transport) → (*EEPROM24AA02UIDFull, error)
	if err != nil {
		panic(err)
	}

	uid, err := chip.ReadUID() // Read 32-bit unique serial number, () → ([4]byte, error)
	if err != nil {
		panic(err)
	}
	// reads 4 bytes at 0xFC-0xFF
	fmt.Printf("UID: %02X%02X%02X%02X\n", uid[0], uid[1], uid[2], uid[3])

	mfr, err := chip.ReadManufacturerCode() // Read manufacturer code, () → (byte, error)
	if err != nil {
		panic(err)
	}
	// reads 0xFA; expect 0x29 (Microchip)

	dev, err := chip.ReadDeviceCode() // Read device code, () → (byte, error)
	if err != nil {
		panic(err)
	}
	// reads 0xFB; expect 0x41
	fmt.Printf("MFR: 0x%02X  DEV: 0x%02X\n", mfr, dev)

	first, err := chip.ReadUserByte(0x00) // Read a single byte, (address=0x00–0x7F) → (byte, error)
	if err != nil {
		panic(err)
	}
	// random read at user EEPROM address
	fmt.Printf("First byte: 0x%02X\n", first)

	if err := chip.WriteUserByte(0x10, 0xA5); err != nil { // Write a single byte, (address, value) → error
		panic(err)
	}
	// byte write + wait until complete (max 5 ms)
	verify, err := chip.ReadUserByte(0x10) // Read a single byte, (address=0x00–0x7F) → (byte, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Wrote 0xA5, read back: 0x%02X\n", verify)

	block, err := chip.Read(0x20, 8) // Sequential read, (address, length) → ([]byte, error)
	if err != nil {
		panic(err)
	}
	// reads 8 bytes starting at address
	fmt.Printf("Block @ 0x20: % X\n", block)

	if err := chip.WritePage(0x40, []byte{0x01, 0x02, 0x03, 0x04}); err != nil { // Page write, (address, data) → error
		panic(err)
	}
	// writes up to 8 bytes within one page
	if err := chip.Write(0x44, []byte{0xAA, 0xBB, 0xCC, 0xDD, 0xEE}); err != nil { // Arbitrary-length write, (address, data) → error
		panic(err)
	}
	// splits at 8-byte page boundaries; waits for each chunk
	fmt.Println("Multi-page write complete")
}

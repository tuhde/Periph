//go:build linux && !tinygo

// MB85RC complete example — Linux host.
//
// Exercises every method of MB85RCFull.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x50"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnectionWithWidth(bus, uint8(addr), 2, nil, nil) // Create I2C connection, (bus=1, addr=0x50, regBytes=2) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := memory.NewMB85RCFull(conn) // Create MB85RC driver, (connection) → (*MB85RCFull, error)
	chk(err)

	fmt.Println("Size:", chip.Size(), "bytes") // Get array size, () → int
	// always 32768 for the MB85RC256V

	chk(chip.SetMaxChunk(16))                 // Set max bytes per bus transaction, (n=30) → error
	fmt.Println("MaxChunk:", chip.MaxChunk()) // Get max bytes per bus transaction, () → int

	chk(chip.WriteUserByte(0x0010, 0xA5)) // Write a single byte, (address 0–32767, value 0–255) → error
	// no write cycle, no polling
	b, err := chip.ReadUserByte(0x0010) // Read a single byte, (address 0–32767) → (byte, error)
	// random read
	chk(err)
	fmt.Printf("Byte: 0x%02X\n", b)

	block := make([]byte, 40)
	for i := range block {
		block[i] = byte(i)
	}
	chk(chip.Write(0x0100, block)) // Sequential write, (address, data) → error
	// split into MaxChunk-byte transactions
	data, err := chip.Read(0x0100, 40) // Sequential read, (address, length) → ([]byte, error)
	// split into MaxChunk-byte transactions
	chk(err)
	fmt.Printf("Block: %X\n", data)

	chk(chip.Fill(0x0100, 40, 0xFF)) // Fill a range, (address, length, value) → error
	// writes value over the range in chunks
	head, err := chip.Read(0x0100, 8) // Sequential read, (address, length) → ([]byte, error)
	chk(err)
	fmt.Printf("After fill: %X\n", head)

	idConn, err := connection.NewI2CConnection(bus, 0x7C, nil, nil) // Create Device ID connection, (bus=1, addr=0x7C) → (*I2CConnection, error)
	if err != nil {
		fmt.Println("Device ID not readable on this bus")
		return
	}
	defer idConn.Close()
	mfr, den, prod, err := chip.ReadDeviceID(idConn, uint8(addr)) // Read Device ID, (idConn, address=0x50) → (uint16, uint16, uint16, error)
	// reads 3 bytes via reserved slave address 0x7C
	if err != nil {
		fmt.Println("Device ID not readable on this bus")
		return
	}
	fmt.Printf("Device ID: mfr=0x%03X density=0x%X product=0x%02X\n", mfr, den, prod)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

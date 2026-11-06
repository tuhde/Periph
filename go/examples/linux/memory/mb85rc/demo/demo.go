//go:build linux && !tinygo

// MB85RC demo example — Linux host.
//
// Persistent boot counter plus a 1,000-record ring-buffer logger written
// back-to-back with no delays, with throughput measurement.
package main

import (
	"encoding/binary"
	"fmt"
	"os"
	"strconv"
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

	// --- Persistent boot counter ---
	// A 4-byte big-endian counter at 0x0000 survives power loss. FRAM has no
	// write cycle, so the increment is committed the moment the write ends.
	raw, err := chip.Read(0x0000, 4) // Sequential read, (address, length) → ([]byte, error)
	chk(err)
	counter := binary.BigEndian.Uint32(raw) + 1
	var cbuf [4]byte
	binary.BigEndian.PutUint32(cbuf[:], counter)
	chk(chip.Write(0x0000, cbuf[:])) // Sequential write, (address, data) → error
	fmt.Println("Boot count:", counter)

	// --- Ring-buffer logger, back-to-back with no delays ---
	// 1,000 records of 16 bytes (index + counter + padding) are written one
	// after the other without any pause — an EEPROM would need ACK polling
	// after every page, FRAM does not.
	const logBase, record, count, ring = 0x0100, 16, 1000, 200
	t0 := time.Now()
	rec := make([]byte, record)
	for i := 0; i < count; i++ {
		binary.BigEndian.PutUint32(rec[0:4], uint32(i))
		binary.BigEndian.PutUint32(rec[4:8], counter)
		chk(chip.Write(logBase+(i%ring)*record, rec)) // Sequential write, (address, data) → error
	}
	dt := time.Since(t0).Seconds()
	fmt.Printf("Logged %d bytes in %.3f s = %d B/s\n", count*record, dt, int(float64(count*record)/dt))

	// --- Verify the last five records ---
	// Read back the newest records from the ring and check their contents.
	ok := true
	for i := count - 5; i < count; i++ {
		r, err := chip.Read(logBase+(i%ring)*record, record) // Sequential read, (address, length) → ([]byte, error)
		chk(err)
		if binary.BigEndian.Uint32(r[0:4]) != uint32(i) || binary.BigEndian.Uint32(r[4:8]) != counter {
			ok = false
		}
	}
	fmt.Println("Last 5 records verified:", ok)

	// --- Clear the log area ---
	// Fill zeroes the whole ring in chunked transactions.
	chk(chip.Fill(logBase, ring*record, 0))                       // Fill a range, (address, length, value) → error
	fmt.Println("Log cleared; array size:", chip.Size(), "bytes") // Get array size, () → int

	// --- Device ID (needs the reserved address 0x7C) ---
	// Some hosts (Linux i2c-dev) refuse addresses 0x78-0x7F; skip then.
	idConn, err := connection.NewI2CConnection(bus, 0x7C, nil, nil) // Create Device ID connection, (bus=1, addr=0x7C) → (*I2CConnection, error)
	if err != nil {
		fmt.Println("Device ID not available")
		return
	}
	defer idConn.Close()
	m, d, p, err := chip.ReadDeviceID(idConn, uint8(addr)) // Read Device ID, (idConn, address=0x50) → (uint16, uint16, uint16, error)
	if err != nil {
		fmt.Println("Device ID not available")
		return
	}
	fmt.Printf("Device ID: mfr=0x%03X density=0x%X product=0x%02X\n", m, d, p)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

//go:build linux && !tinygo

// MX25L12845E NOR flash demo — an append-only record log on bare NOR flash.
// Platform: Linux host (env SPI_BUS, SPI_DEVICE).
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

const (
	ring    = 0xFFC000
	sectors = 4
	slotLen = 32
	slots   = sectors * 4096 / slotLen
)

func run(conn connection.Connection) {
	// --- Bring up the chip ---
	// Plain single-I/O SPI: the driver releases deep power-down, checks the JEDEC ID and
	// waits until the chip is idle. The serial-number area is the factory ESN on parts that have one.
	flash, err := memory.NewMX25L12845EFull(conn, memory.MX25L12845EOptions{}) // Create MX25L12845E driver and check the ID, (connection, options) → (*MX25L12845EFull, error)
	if err != nil {
		panic(err)
	}
	esn, _ := flash.SerialNumber() // Read the serial-number OTP area, () → ([16]byte, error)
	fmt.Printf("MX25L12845E, %d MiB, serial % X\n", flash.Size()/(1024*1024), esn)

	// --- Append records to a ring of four sectors ---
	// 32-byte records (sequence, tick, payload) fill a sector, 128 per sector. A blank slot
	// (all 0xFF) needs no erase, so appending is just a page program; only when the writer
	// enters the next sector is that sector erased first — one erase per 128 records.
	for s := uint32(0); s < sectors; s++ {
		_ = flash.EraseSector(ring + s*4096) // Erase one 4 KiB sector, (address) → error
	}
	t0 := time.Now()
	erases, slot := 0, uint32(0)
	for seq := uint32(0); seq < 300; seq++ {
		if slot%128 == 0 && seq >= slots { // wrapped into the next sector: make it blank
			_ = flash.EraseSector(ring + (slot/128)*4096)
			erases++
		}
		rec := make([]byte, slotLen)
		for i := range rec {
			rec[i] = 0xA5
		}
		binary.LittleEndian.PutUint32(rec[0:], seq)
		binary.LittleEndian.PutUint32(rec[4:], uint32(time.Since(t0).Milliseconds()))
		_ = flash.PageProgram(ring+slot*slotLen, rec) // Program one record (inside a page), (address, data) → error
		slot = (slot + 1) % slots
	}
	fmt.Printf("appended 300 records in %d ms, %d sector erases\n", time.Since(t0).Milliseconds(), erases)

	// --- Verify by scanning the ring ---
	// Every slot must carry a valid sequence number and the 0xA5 payload.
	good, newest := 0, uint32(0)
	rec := make([]byte, slotLen)
	for s := uint32(0); s < slots; s++ {
		_ = flash.ReadBytes(ring+s*slotLen, rec) // Read bytes, (address, buf) → error
		seq := binary.LittleEndian.Uint32(rec)
		if seq != 0xFFFFFFFF && rec[20] == 0xA5 {
			good++
			if seq > newest {
				newest = seq
			}
		}
	}
	fmt.Printf("ring holds %d valid records, newest sequence number %d\n", good, newest)

	// --- Leave the chip clean ---
	for s := uint32(0); s < sectors; s++ {
		_ = flash.EraseSector(ring + s*4096)
	}
	fmt.Println("ring erased")
}

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}
	conn, err := connection.NewSPIConnection(bus, device, 0, 8_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, mode=0, max_speed=8 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()
	run(conn)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

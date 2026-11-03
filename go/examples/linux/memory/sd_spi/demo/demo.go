//go:build linux && !tinygo

// SD card (SPI mode) demo — filesystem-free data logger on the last blocks of the card.
// Platform: Linux host (CS on a GPIO via SPI_NO_CS; env SPI_BUS, SPI_DEVICE, CS_LINE).
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

const demoBlocks = 64

func run(conn connection.SPIExtensions) {
	// --- Bring up the card ---
	// The card starts in SD mode; the driver sends 80 clocks with CS high, CMD0 with CS
	// low to enter SPI mode, then negotiates the card type. SCK runs at 400 kHz during
	// init and is raised to 12 MHz afterwards.
	sd, err := memory.NewSDSPIFull(conn, memory.SDSPIOptions{}) // Create SD driver and init the card, (connection, options) → (*SDSPIFull, error)
	if err != nil {
		panic(err)
	}
	info := sd.Info() // Geometry and capabilities, () → storage.BlockInfo
	fmt.Printf("card %s, %d MiB\n", info.Name, info.BlockCount/2048)

	// --- Log samples to a raw block range (filesystem-free data logger) ---
	// Each 512-byte block holds a header (sequence number, tick) and 126 samples.
	// The last blocks of the card are used and restored afterwards, so a card that
	// carries a filesystem keeps its data (only the tail of the volume is touched).
	first := sd.BlockCount() - demoBlocks
	saved := make([]byte, demoBlocks*512)
	_ = sd.Read(first, saved) // Read blocks, (block, buf) → error
	t0 := time.Now()
	block := make([]byte, 512)
	for seq := uint32(0); seq < demoBlocks; seq++ {
		binary.LittleEndian.PutUint32(block[0:], seq)
		binary.LittleEndian.PutUint32(block[4:], uint32(time.Since(t0).Milliseconds()))
		for i := 2; i < 128; i++ {
			binary.LittleEndian.PutUint32(block[i*4:], seq*1000+uint32(i))
		}
		_ = sd.Write(first+seq, block) // Write blocks, (block, data) → error
	}
	_ = sd.Sync() // Wait until programming is done, () → error
	fmt.Printf("wrote %d blocks in %d ms\n", demoBlocks, time.Since(t0).Milliseconds())

	// --- Verify by reading back ---
	// Read every block back and recompute the expected sample values; any single
	// mismatch (or a CRC16 error returned by the driver) is counted.
	bad := 0
	for seq := uint32(0); seq < demoBlocks; seq++ {
		if err := sd.Read(first+seq, block); err != nil { // Read blocks, (block, buf) → error
			bad++
			continue
		}
		if binary.LittleEndian.Uint32(block[0:]) != seq {
			bad++
			continue
		}
		for i := 2; i < 128; i++ {
			if binary.LittleEndian.Uint32(block[i*4:]) != seq*1000+uint32(i) {
				bad++
				break
			}
		}
	}
	fmt.Printf("verify: %d bad blocks\n", bad)

	// --- Restore the original contents ---
	_ = sd.Write(first, saved)
	_ = sd.Sync()
	fmt.Println("restored")
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
	csLine, err := strconv.Atoi(envOr("CS_LINE", "25"))
	if err != nil {
		panic(err)
	}

	cs, err := connection.NewGpioOutputPin(csLine) // Create CS pin, (line=25) → (*GpioOutputPin, error)
	if err != nil {
		panic(err)
	}
	conn, err := connection.NewSPIConnectionGPIOCS(bus, device, 0, 400_000, cs, nil, nil) // Create SPI connection with GPIO chip select, (bus, device, mode=0, max_speed, cs) → (*SPIConnection, error)
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

//go:build tinygo

// SD card (SPI mode) demo — filesystem-free data logger on the last blocks of the card.
// Platform: TinyGo / Raspberry Pi Pico W (machine.SPI0: GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS).
package main

import (
	"encoding/binary"
	"fmt"
	"machine"
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
	cfg := machine.SPIConfig{
		Frequency: 400_000, // SD init needs <= 400 kHz; the driver raises it afterwards
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}
	spi := machine.SPI0
	if err := spi.Configure(cfg); err != nil {
		panic(err)
	}
	time.Sleep(2 * time.Second)

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	conn.EnableSpeedChange(cfg)                                      // Allow SetSpeed() to reconfigure the SPI, (cfg)
	run(conn)
}

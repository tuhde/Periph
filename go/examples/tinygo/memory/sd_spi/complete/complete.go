//go:build tinygo

// SD card (SPI mode) complete example — exercises every method of SDSPIFull.
// Platform: TinyGo / Raspberry Pi Pico W (machine.SPI0: GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS).
package main

import (
	"bytes"
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func run(conn connection.SPIExtensions) {
	sd, err := memory.NewSDSPIFull(conn, memory.SDSPIOptions{}) // Create SD driver and init the card, (connection, options) → (*SDSPIFull, error)
	if err != nil {
		panic(err)
	}

	fmt.Println("type:", sd.CardType()) // Card generation, () → SDCardType
	info := sd.Info()                   // Geometry and capabilities, () → storage.BlockInfo
	// block size, count, erase unit, name from the CID
	fmt.Printf("%+v\n", info)
	cid, _ := sd.CID() // Read CID register, () → ([16]byte, error)
	// manufacturer, name, revision, serial, date
	fmt.Printf("CID: % X\n", cid)
	csd := sd.CSD() // CSD register read at init, () → [16]byte
	fmt.Printf("CSD: % X\n", csd)
	ocr, _ := sd.OCR() // Read OCR, () → (uint32, error)
	// voltage window and CCS (block addressing) bit
	fmt.Printf("OCR: %08X\n", ocr)
	present, _ := sd.IsPresent() // Card responds, () → (bool, error)
	fmt.Println("present:", present)

	// Use the last 8 blocks of the card as scratch, restoring their contents.
	scratch := sd.BlockCount() - 8
	saved := make([]byte, 8*512)
	_ = sd.Read(scratch, saved) // Read blocks, (block, buf) → error
	// CMD18 multi-block read for more than one block
	pattern := make([]byte, 8*512)
	for i := range pattern {
		pattern[i] = byte(i*7 + 3)
	}
	fmt.Println("write:", sd.Write(scratch, pattern)) // Write blocks, (block, data) → error
	// CMD25 multi-block write with stop token
	fmt.Println("sync:", sd.Sync()) // Wait until programming is done, () → error
	back := make([]byte, 8*512)
	_ = sd.Read(scratch, back)
	fmt.Println("roundtrip:", bytes.Equal(pattern, back))

	fmt.Println("set_crc(false):", sd.SetCRC(false)) // Disable CRC checking, (enabled) → error
	// CMD59 off; data CRC16 is no longer verified
	_ = sd.SetCRC(true)
	fmt.Println("set_speed:", sd.SetSpeed(6_000_000)) // Change SCK frequency, (hz) → error
	// reconfigures the SPI clock
	fmt.Println("erase:", sd.Erase(scratch, 8)) // Erase blocks, (block, count) → error
	// CMD32/33/38; contents afterwards are card dependent
	_ = sd.Write(scratch, saved) // restore the original contents
	fmt.Println("done")
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

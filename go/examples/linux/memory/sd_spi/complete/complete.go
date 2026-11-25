//go:build linux && !tinygo

// SD card (SPI mode) complete example — exercises every method of SDSPIFull.
// Platform: Linux host (CS on a GPIO via SPI_NO_CS; env SPI_BUS, SPI_DEVICE, CS_LINE).
package main

import (
	"bytes"
	"fmt"
	"os"
	"strconv"

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

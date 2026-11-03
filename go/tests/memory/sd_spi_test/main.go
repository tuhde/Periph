//go:build linux && !tinygo

// SD card (SPI mode) hardware test — init, CID/OCR, single and multi-block round-trips in the last 8 blocks (contents saved and restored).
// Platform: Linux host (CS on a GPIO via SPI_NO_CS; env SPI_BUS, SPI_DEVICE, CS_LINE).
package main

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/storage"
)

func run(conn connection.SPIExtensions) {
	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}
	done := func() { fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed) }

	sd, err := memory.NewSDSPIFull(conn, memory.SDSPIOptions{}) // Create SD driver and init the card, (connection, options) → (*SDSPIFull, error)
	check("init", err == nil)
	if err != nil {
		fmt.Println("init error:", err)
		done()
		return
	}
	info := sd.Info()
	fmt.Printf("type=%d blocks=%d name=%s\n", sd.CardType(), info.BlockCount, info.Name)
	check("card_type_detected", sd.CardType() != memory.SDCardNone)
	check("geometry", info.BlockCount > 0 && info.BlockSize == 512)
	check("no_erase_needed", sd.EraseSize() == 512 && !sd.NeedsErase())
	present, _ := sd.IsPresent()
	check("is_present", present)
	_, err = sd.CID()
	check("cid_read", err == nil)
	ocr, err := sd.OCR()
	check("ocr_has_3v3_window", err == nil && ocr&0x00FF8000 != 0)

	// Scratch area: the last 8 blocks, contents saved and restored.
	scratch := sd.BlockCount() - 8
	saved := make([]byte, 8*512)
	check("save_scratch", sd.Read(scratch, saved) == nil)
	pat := make([]byte, 8*512)
	for i := range pat {
		pat[i] = byte(i*7 + 3)
	}
	back := make([]byte, 8*512)

	check("write_single", sd.Write(scratch, pat[:512]) == nil)
	check("single_roundtrip", sd.Read(scratch, back[:512]) == nil && bytes.Equal(back[:512], pat[:512]))
	check("write_multi", sd.Write(scratch, pat) == nil)
	check("sync", sd.Sync() == nil)
	check("multi_roundtrip", sd.Read(scratch, back) == nil && bytes.Equal(back, pat))
	check("out_of_range", errors.Is(sd.Read(sd.BlockCount(), back[:512]), storage.ErrOutOfRange))

	check("crc_off", sd.SetCRC(false) == nil)
	check("read_with_crc_off", sd.Read(scratch, back[:512]) == nil && bytes.Equal(back[:512], pat[:512]))
	check("crc_on", sd.SetCRC(true) == nil)
	check("set_speed_8mhz", sd.SetSpeed(8_000_000) == nil)
	check("read_at_higher_speed", sd.Read(scratch, back) == nil && bytes.Equal(back, pat))

	check("restore_scratch", sd.Write(scratch, saved) == nil)
	check("final_sync", sd.Sync() == nil)
	done()
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

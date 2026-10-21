//go:build tinygo

// SD card (SPI mode) hardware test — init, CID/OCR, single and multi-block round-trips in the last 8 blocks (contents saved and restored).
// Platform: TinyGo / Raspberry Pi Pico W (machine.SPI0: GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS).
package main

import (
	"bytes"
	"errors"
	"fmt"
	"machine"
	"time"

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

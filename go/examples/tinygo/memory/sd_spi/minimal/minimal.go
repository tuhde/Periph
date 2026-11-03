//go:build tinygo

// SD card (SPI mode) minimal example — reads and prints block 0 (read-only).
// Platform: TinyGo / Raspberry Pi Pico W (machine.SPI0: GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS).
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func run(conn connection.SPIExtensions) {
	sd, err := memory.NewSDSPIMinimal(conn, memory.SDSPIOptions{}) // Create SD driver and init the card, (connection, options) → (*SDSPIMinimal, error)
	if err != nil {
		panic(err)
	}

	fmt.Printf("capacity: %d blocks = %d MiB\n", sd.BlockCount(), sd.BlockCount()/2048) // Capacity in 512-byte blocks, () → uint32
	block := make([]byte, 512)
	for {
		if err := sd.ReadBlock(0, block); err != nil { // Read one 512-byte block, (block, buf) → error
			fmt.Println("read:", err)
		}
		fmt.Printf("block 0: % X\n", block[:16])
		fmt.Printf("boot signature: %02X%02X\n", block[510], block[511])
		time.Sleep(2 * time.Second)
	}
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

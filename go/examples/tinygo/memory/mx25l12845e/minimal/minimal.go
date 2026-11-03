//go:build tinygo

// MX25L12845E NOR flash minimal example — writes and reads back a counter in the scratch sector.
// Platform: TinyGo / Raspberry Pi Pico W (machine.SPI0: GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS).
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
const scratch = 0xFF0000

func run(conn connection.Connection) {
	flash, err := memory.NewMX25L12845EMinimal(conn, memory.MX25L12845EOptions{}) // Create MX25L12845E driver and check the ID, (connection, options) → (*MX25L12845EMinimal, error)
	if err != nil {
		panic(err)
	}
	id, _ := flash.JEDECID()                                           // Read JEDEC ID, () → ([3]byte, error)
	fmt.Printf("JEDEC ID % X, %d MiB\n", id, flash.Size()/(1024*1024)) // Capacity in bytes, () → uint32

	for counter := byte(0); counter < 10; counter++ {
		_ = flash.WriteBytes(scratch, []byte{counter}) // Write bytes (erases the sector when needed), (address, data) → error
		value := make([]byte, 1)
		_ = flash.ReadBytes(scratch, value) // Read bytes, (address, buf) → error
		fmt.Printf("wrote 0x%02X, read 0x%02X\n", counter, value[0])
		time.Sleep(500 * time.Millisecond)
	}
	_ = flash.EraseSector(scratch) // Erase one 4 KiB sector, (address) → error
	fmt.Println("done, scratch sector erased")
}

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 8_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}
	time.Sleep(2 * time.Second)
	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	run(conn)
}

//go:build tinygo

// 23X1024 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.SPI0 (GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS, SPI Mode 0).
// Writes and reads back an incrementing counter at address 0 twice per second.
package main

import (
	"machine"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

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

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)

	chip, err := memory.NewSRAM23X1024Minimal(conn) // Create 23X1024 driver, (connection) → (*SRAM23X1024Minimal, error)
	if err != nil {
		panic(err)
	}

	for counter := byte(0); ; counter++ {
		if err := chip.WriteByteAt(0x00000, counter); err != nil { // Write one byte, (address 0–131071, value 0–255) → error
			println("sram:", err.Error())
			continue
		}
		value, err := chip.ReadByteAt(0x00000) // Read one byte, (address 0–131071) → (byte, error)
		if err != nil {
			println("sram:", err.Error())
			continue
		}
		println("wrote", strconv.FormatInt(int64(counter), 16), "read", strconv.FormatInt(int64(value), 16))
		time.Sleep(500 * time.Millisecond)
	}
}

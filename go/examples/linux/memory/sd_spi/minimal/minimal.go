//go:build linux && !tinygo

// SD card (SPI mode) minimal example — reads and prints block 0 (read-only).
// Platform: Linux host (CS on a GPIO via SPI_NO_CS; env SPI_BUS, SPI_DEVICE, CS_LINE).
package main

import (
	"fmt"
	"os"
	"strconv"
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

//go:build linux && !tinygo

// MX25L12845E NOR flash minimal example — writes and reads back a counter in the scratch sector.
// Platform: Linux host (env SPI_BUS, SPI_DEVICE).
package main

import (
	"fmt"
	"os"
	"strconv"
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
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}
	conn, err := connection.NewSPIConnection(bus, device, 0, 8_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, mode=0, max_speed=8 MHz) → (*SPIConnection, error)
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

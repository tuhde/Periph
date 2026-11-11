//go:build linux && !tinygo

// PCA6408A minimal example — Linux host. Reads P0 and mirrors it to P7 every 200 ms.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x20"), 0, 8)
	if err != nil {
		panic(err)
	}
	devAddr := uint8(addr)

	conn, err := connection.NewI2CConnection(bus, devAddr, nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := ioexpander.NewPCA6408AMinimal(conn, devAddr) // Create PCA6408A driver, (connection, addr=0x20) → (*PCA6408AMinimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0)                      // Get pin proxy, (n=0) → PCA6408APin
	p7 := chip.Pin(7)                      // Get pin proxy, (n=7) → PCA6408APin
	_ = p7.Mode(ioexpander.PCA6408AOutput) // Make P7 an output, (m PinMode) → error

	for {
		port, err := chip.ReadPort(0) // Read all 8 pins, (port=0) → (uint8, error)
		if err != nil {
			panic(err)
		}
		level, err := p0.Read() // Read actual level, () → (bool, error)
		if err != nil {
			panic(err)
		}
		_ = p7.Write(level) // Write the output latch, (high bool) → error
		fmt.Printf("PORT=0x%02X  P0=%t\n", port, level)
		time.Sleep(200 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

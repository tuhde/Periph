//go:build linux && !tinygo

// PCA6416A minimal example — Linux host. Reads P0_0 and mirrors it to P1_7 every 200 ms.
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

	chip, err := ioexpander.NewPCA6416AMinimal(conn, devAddr) // Create PCA6416A driver, (connection, addr=0x20) → (*PCA6416AMinimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0)                       // Get pin proxy, (n=0) → PCA6416APin
	p15 := chip.Pin(15)                     // Get pin proxy, (n=15) → PCA6416APin
	_ = p15.Mode(ioexpander.PCA6416AOutput) // Make P1_7 an output, (m PinMode) → error

	for {
		port, err := chip.ReadPort(1) // Read the 8 pins of port 1, (port) → (uint8, error)
		if err != nil {
			panic(err)
		}
		level, err := p0.Read() // Read actual level, () → (bool, error)
		if err != nil {
			panic(err)
		}
		_ = p15.Write(level) // Write the output latch, (high bool) → error
		fmt.Printf("PORT1=0x%02X  P0=%t\n", port, level)
		time.Sleep(200 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

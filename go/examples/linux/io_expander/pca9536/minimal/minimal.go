//go:build linux && !tinygo

// PCA9536 minimal example — Linux host. Reads P2 and mirrors it to P0 every 200 ms.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x41"), 0, 8)
	if err != nil {
		panic(err)
	}
	devAddr := uint8(addr)

	conn, err := connection.NewI2CConnection(bus, devAddr, nil, nil) // Create I2C connection, (bus=1, addr=0x41, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := ioexpander.NewPCA9536Minimal(conn, devAddr) // Create PCA9536 driver, (connection, addr=0x41) → (*PCA9536Minimal, error)
	if err != nil {
		panic(err)
	}

	p2 := chip.Pin(2)                     // Get pin proxy, (n=2) → PCA9536Pin
	p0 := chip.Pin(0)                     // Get pin proxy, (n=0) → PCA9536Pin
	_ = p0.Mode(ioexpander.PCA9536Output) // Make P0 an output, (m PinMode) → error

	for {
		port, err := chip.ReadPort(0) // Read all 4 pins, (port=0) → (uint8, error)
		if err != nil {
			panic(err)
		}
		level, err := p2.Read() // Read actual level, () → (bool, error)
		if err != nil {
			panic(err)
		}
		_ = p0.Write(level) // Write the output latch, (high bool) → error
		fmt.Printf("PORT=0x%X  P2=%t\n", port, level)
		time.Sleep(200 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

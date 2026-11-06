//go:build linux && !tinygo

// MCP23008 minimal example — Linux host. Reads GP0 and mirrors it to GP7 every 200 ms.
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

	chip, err := ioexpander.NewMCP23008Minimal(conn, devAddr) // Create MCP23008 driver, (connection, addr=0x20) → (*MCP23008Minimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0)                      // Get pin proxy, (n=0) → MCP23008Pin
	p7 := chip.Pin(7)                      // Get pin proxy, (n=7) → MCP23008Pin
	_ = p7.Mode(ioexpander.MCP23008Output) // Make GP7 an output, (m PinMode) → error

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
		fmt.Printf("GPIO=0x%02X  GP0=%t\n", port, level)
		time.Sleep(200 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

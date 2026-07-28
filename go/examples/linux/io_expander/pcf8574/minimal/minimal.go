//go:build linux && !tinygo

// PCF8574 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection and reads a
// button on P4 to drive an LED on P0 every 200 ms.
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

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x20, intPin=nil, enPin=nil) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := ioexpander.NewPCF8574Minimal(conn, uint8(addr)) // Create PCF8574 driver, (connection, addr=0x20) → (*PCF8574Minimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get pin proxy, (n=0) → PCF8574Pin
	p4 := chip.Pin(4) // Get pin proxy, (n=4) → PCF8574Pin

	p0.Set(false) // Drive low, (high=false) → error

	for {
		port, err := chip.ReadPort0() // Read all 8 pins, () → (uint8, error)
		if err != nil {
			panic(err)
		}
		btn, err := p4.Get() // Read actual level, () → (bool, error)
		if err != nil {
			panic(err)
		}
		if btn {
			_ = p0.Set(true) // Set high (quasi-input), (high=true) → error
		} else {
			_ = p0.Set(false) // Drive low, (high=false) → error
		}
		fmt.Printf("port=0x%02X  P4=%d\n", port, boolToInt(btn))
		time.Sleep(200 * time.Millisecond)
	}
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

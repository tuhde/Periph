//go:build linux && !tinygo

// PCF8576 minimal example — Linux host.
//
// Constructs the driver on /dev/i2c-N, then writes four 7-segment digits
// to a 1:4 multiplex LCD panel.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	lcd, err := display.NewPCF8576Minimal(conn) // Create PCF8576 driver, (connection) → (*PCF8576Minimal, error)
	if err != nil {
		panic(err)
	}

	digits := [4]uint8{1, 2, 3, 4}
	for i, d := range digits {
		seg := display.PCF8576SevenSeg[d] // Encode 7-segment digit, (digit 0–9) → uint8
		if err := lcd.SetDigit7seg(uint8(i), seg); err != nil { // Write one digit, (position 0–19, segments 0–255) → error
			panic(err)
		}
	}
	fmt.Println("wrote 1234")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

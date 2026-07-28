//go:build linux && !tinygo

// PCF8576 demo example — Linux host.
//
// 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD,
// stepping once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

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

	// --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
	// The PCF8576 drives four 7-segment digits from a single I2C bus; the host
	// encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
	// and writes all four with one WriteRaw() call. The countdown runs once per
	// second and the terminal mirrors the value sent to the display.
	lcd, err := display.NewPCF8576Full(conn) // Create PCF8576 driver, (connection) → (*PCF8576Full, error)
	if err != nil {
		panic(err)
	}

	for n := 9999; n >= 0; n-- {
		out := [4]uint8{
			display.PCF8576SevenSeg[(n/1000)%10], // Encode 7-segment digit, (digit 0–9) → uint8
			display.PCF8576SevenSeg[(n/100)%10],  // Encode 7-segment digit, (digit 0–9) → uint8
			display.PCF8576SevenSeg[(n/10)%10],   // Encode 7-segment digit, (digit 0–9) → uint8
			display.PCF8576SevenSeg[n%10],        // Encode 7-segment digit, (digit 0–9) → uint8
		}
		if err := lcd.WriteRaw(0, out[:]); err != nil { // Write all four digits, (address 0, 4 bytes) → error
			panic(err)
		}
		fmt.Printf("countdown: %04d\n", n)
		time.Sleep(time.Second)
	}

	// --- Stop indicator: light only the middle segments (g) on every digit ---
	// When the counter reaches zero we replace the "0000" pattern with "----" to
	// signal that the demo has finished. Each digit's g segment is bit 1, so a
	// 0x02 byte lights just the bar across the middle.
	dash := [4]uint8{0x02, 0x02, 0x02, 0x02}
	if err := lcd.WriteRaw(0, dash[:]); err != nil { // Write dash pattern, (address 0, 4 bytes) → error
		panic(err)
	}
	fmt.Println("countdown complete")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

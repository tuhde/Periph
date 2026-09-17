//go:build linux && !tinygo

// MPR121 demo example — Linux host.
//
// 12-button musical keyboard with bitmask diffing.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/chips/other"
)

var notes = [12]string{"C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4"}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5A"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5A) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := other.NewMPR121Full(conn) // Create MPR121 driver, (connection) → (*MPR121Full, error)
	if err != nil {                        // defaults, all 12 electrodes enabled
		panic(err)
	}
	var previous uint16

	for {
		mask, err := chip.Touched() // Read 12-bit touch bitmask, () → (uint16 bitmask, error)
		if err != nil {
			panic(err)
		}
		newlyPressed := mask & ^previous
		newlyReleased := ^mask & previous
		for n := uint8(0); n < 12; n++ {
			if newlyPressed&(1<<n) != 0 {
				fmt.Printf("NOTE ON:  %s\n", notes[n])
			}
			if newlyReleased&(1<<n) != 0 {
				fmt.Printf("NOTE OFF: %s\n", notes[n])
			}
		}
		previous = mask
		time.Sleep(50 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

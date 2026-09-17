//go:build tinygo

// MPR121 demo example — TinyGo / Raspberry Pi Pico W.
//
// 12-button musical keyboard with bitmask diffing.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/other"
	"github.com/tuhde/Periph/go/periph/connection"
)

var notes = [12]string{"C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4"}

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x5A, nil, nil) // Create I2C connection, (i2c, addr=0x5A) → (*I2CConnection)
	chip, err := other.NewMPR121Full(conn)                    // Create MPR121 driver, (connection) → (*MPR121Full, error)
	if err != nil {                                           // defaults, all 12 electrodes enabled
		panic(err)
	}
	var previous uint16

	for {
		mask, err := chip.Touched()
		if err != nil {
			fmt.Printf("touched: %v\n", err)
			time.Sleep(time.Second)
			continue
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

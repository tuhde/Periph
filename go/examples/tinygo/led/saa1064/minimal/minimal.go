//go:build tinygo

// SAA1064 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Counts 0–99 on the four digits. Configures machine.I2C1 with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	must(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil) // Create I2C connection, (i2c, addr=0x38) → (*I2CConnection)

	display, err := led.NewSAA1064Minimal(conn) // Create SAA1064 driver, (connection) → (*SAA1064Minimal, error)
	must(err)

	for n := 0; n < 100; n++ {
		must(display.ShowNumber(n)) // Show a right-aligned number, (value 0–9999) → error
		time.Sleep(100 * time.Millisecond)
	}
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

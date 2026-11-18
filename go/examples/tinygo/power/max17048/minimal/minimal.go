//go:build tinygo

// Max17048 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then reads cell voltage, state of charge and
// temperature every 5 seconds.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x36, nil, nil) // Create I2C connection, (i2c, addr=0x36) → (*I2CConnection)
	chip, err := power.NewMax17048Minimal(conn, 1)           // Create MAX17048 driver, (connection, cells) → (*Max17048Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		v := must(chip.Voltage()) // Read cell voltage, () → (float32 V, error)
		p := must(chip.Percent()) // Read state of charge, () → (float32 %, error)
		fmt.Printf("%.3f V  %.1f %%\n", v, p)
		time.Sleep(time.Second)
	}
}

// must unwraps a (value, error) pair, panicking on error.
func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

// check panics on error.
func check(err error) {
	if err != nil {
		panic(err)
	}
}

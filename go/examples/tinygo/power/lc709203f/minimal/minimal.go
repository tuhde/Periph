//go:build tinygo

// LC709203F minimal example — TinyGo / Raspberry Pi Pico W.
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

	conn := connection.NewI2CConnection(i2c, 0x0B, nil, nil) // Create I2C connection, (i2c, addr=0x0B) → (*I2CConnection)
	chip, err := power.NewLC709203FMinimal(conn, 1000, 1)    // Create LC709203F driver, (connection, capacityMah, profile) → (*LC709203FMinimal, error)
	if err != nil {
		panic(err)
	}

	for {
		v := must(chip.Voltage())     // Read cell voltage, () → (float32 V, error)
		p := must(chip.Percent())     // Read state of charge, () → (float32 %, error)
		t := must(chip.Temperature()) // Read cell temperature, () → (float32 °C, error)
		fmt.Printf("%.3f V  %.1f %%  %.2f C\n", v, p, t)
		time.Sleep(5 * time.Second)
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

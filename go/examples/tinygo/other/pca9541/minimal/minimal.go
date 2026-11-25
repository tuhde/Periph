//go:build tinygo

// PCA9541 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Takes and releases the downstream I²C bus.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/other"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, other.PCA9541I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x70) → *I2CConnection
	defer conn.Close()

	selector, err := other.NewPCA9541Minimal(conn) // Create PCA9541 driver, (conn) → (*PCA9541Minimal, error)
	must(err)
	has, err := selector.HasControl() // Report control, () → (bool, error)
	must(err)
	println("has control", has)
	ok, err := selector.Acquire(false, 20) // Take the downstream bus, (busInit bool, timeoutMs int) → (bool, error)
	must(err)
	println("acquire", ok)
	time.Sleep(time.Second)
	must(selector.Release()) // Give the bus to the other master, () → error
}

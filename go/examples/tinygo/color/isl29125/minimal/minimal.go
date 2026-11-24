//go:build tinygo

// ISL29125 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Prints the red, green and blue counts once a second.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/color"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, color.ISL29125I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x44) → *I2CConnection
	defer conn.Close()

	sensor, err := color.NewISL29125Minimal(conn) // Create ISL29125 driver, (conn) → (*ISL29125Minimal, error)
	must(err)                                     // checks DEVICE_ID, starts RGB conversion
	for i := 0; i < 10; i++ {
		c, err := sensor.Read() // Read the three channels, () → (ISL29125Counts{Red, Green, Blue}, error)
		must(err)
		println("R", c.Red, "G", c.Green, "B", c.Blue)
		time.Sleep(time.Second)
	}
}

//go:build tinygo

// APDS-9250 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Prints the red, green, blue and IR counts once a second.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, light.APDS9250I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x52) → *I2CConnection
	defer conn.Close()

	sensor, err := light.NewAPDS9250Minimal(conn) // Create APDS-9250 driver, (conn) → (*APDS9250Minimal, error)
	must(err)                                     // checks PART_ID, starts colour mode
	for i := 0; i < 10; i++ {
		c, err := sensor.Read() // Read the four channels, () → (APDS9250Counts{Red, Green, Blue, IR}, error)
		must(err)
		println("R", c.Red, "G", c.Green, "B", c.Blue, "IR", c.IR)
		time.Sleep(time.Second)
	}
}

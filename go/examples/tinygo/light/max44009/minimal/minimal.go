//go:build tinygo

// MAX44009 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Reads the ambient illuminance once a second.
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
	conn := connection.NewI2CConnection(machine.I2C1, light.MAX44009I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x4A) → *I2CConnection
	defer conn.Close()

	sensor, err := light.NewMAX44009Minimal(conn) // Create MAX44009 driver, (conn) → (*MAX44009Minimal, error)
	must(err)
	for i := 0; i < 10; i++ {
		lx, err := sensor.Lux() // Read illuminance, () → (float32 lux, error)
		must(err)
		println(lx, "lx")
		time.Sleep(time.Second)
	}
}

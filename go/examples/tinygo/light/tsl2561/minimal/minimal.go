//go:build tinygo

// TSL2561 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Reads the illuminance once a second (1× gain, 402 ms, T/FN/CL coefficients).
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
	conn := connection.NewI2CConnection(machine.I2C1, light.TSL2561I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x39) → *I2CConnection
	defer conn.Close()

	sensor, err := light.NewTSL2561Minimal(conn) // Create TSL2561 driver, (conn) → (*TSL2561Minimal, error)
	must(err)
	for i := 0; i < 10; i++ {
		lx, err := sensor.ReadLux() // Read illuminance, () → (float32 lux, error)
		must(err)
		println(lx, "lx")
		time.Sleep(time.Second)
	}
}

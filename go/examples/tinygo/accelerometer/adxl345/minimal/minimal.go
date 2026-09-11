//go:build tinygo

// ADXL345 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn, err := connection.NewI2CConnection(0, 0x53, nil, nil) // Create I2C connection, (bus=0, addr=0x53) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	sensor, err := accelerometer.NewADXL345Minimal(conn, false) // Create ADXL345 driver, (conn, spi=false) → (*ADXL345Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		x, y, z, err := sensor.Read() // Read 3-axis acceleration, () → (float32, float32, float32, error) g, g, g
		if err != nil {
			panic(err)
		}
		println("x=", x, " y=", y, " z=", z, " g")
		time.Sleep(100 * time.Millisecond)
	}
}
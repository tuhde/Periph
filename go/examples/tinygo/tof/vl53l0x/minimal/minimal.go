//go:build tinygo

// VL53L0X minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/tof"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, tof.VL53L0XI2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x29) → *I2CConnection
	defer conn.Close()

	m, err := tof.NewVL53L0XMinimal(conn) // Create VL53L0X driver, (conn) → (*VL53L0XMinimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 50; i++ {
		d, err := m.Distance() // Measure distance, () → (uint16 mm, error)
		if err != nil {
			panic(err)
		}
		if m.RangeValid() { // Check last measurement, () → bool
			println("distance (mm)", d)
		} else {
			println("out of range")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

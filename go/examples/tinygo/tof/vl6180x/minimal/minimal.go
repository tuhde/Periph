//go:build tinygo

// VL6180X minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/tof"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnectionWithWidth(machine.I2C0, tof.VL6180XI2CAddress, 2, nil, nil) // Create I2C connection, (i2c, addr=0x29, regBytes=2) → *I2CConnection
	defer conn.Close()

	m, err := tof.NewVL6180XMinimal(conn) // Create VL6180X driver, (conn) → (*VL6180XMinimal, error)
	must(err)

	for i := 0; i < 50; i++ {
		mm, err := m.ReadRange() // Single-shot range, () → (uint8 mm, error)
		if err != nil {
			println("range error", m.LastError()) // Last range error code, () → uint8
		} else {
			println("mm", mm)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

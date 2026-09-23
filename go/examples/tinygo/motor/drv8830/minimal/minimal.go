//go:build tinygo

// DRV8830 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, motor.DRV8830I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x60) → *I2CConnection
	defer conn.Close()

	m, err := motor.NewDRV8830Minimal(conn) // Create DRV8830 driver, (conn) → (*DRV8830Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 5; i++ {
		if err := m.Drive(3.0); err != nil { // Drive at regulated voltage, (voltage V, + = forward) → error
			panic(err)
		}
		time.Sleep(2 * time.Second)
		if err := m.Drive(-3.0); err != nil { // Drive at regulated voltage, (voltage V, - = reverse) → error
			panic(err)
		}
		time.Sleep(2 * time.Second)
	}

	if err := m.Stop(); err != nil { // Coast to standby, () → error
		panic(err)
	}
}

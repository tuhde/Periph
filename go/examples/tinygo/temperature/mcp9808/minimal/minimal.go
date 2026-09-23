//go:build tinygo

// MCP9808 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, temperature.MCP9808I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x18) → *I2CConnection
	defer conn.Close()

	m, err := temperature.NewMCP9808Minimal(conn) // Create MCP9808 driver, (conn) → (*MCP9808Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		t, err := m.ReadTemperature() // Read ambient temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		println("temperature (m°C)", int32(t*1000))
		time.Sleep(1000 * time.Millisecond)
	}
}

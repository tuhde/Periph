//go:build tinygo

// MCP3422 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Reads both differential inputs.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.MCP3422I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x68) → *I2CConnection
	defer conn.Close()

	adc, err := adcdac.NewMCP3422Minimal(conn) // Create MCP3422 driver, (conn) → (*MCP3422Minimal, error)
	must(err)
	for i := 0; i < 10; i++ {
		raw, err := adc.ReadRaw(0) // Read a signed code, (channel 0–1) → (int32, error)
		must(err)
		v, err := adc.ReadVoltage(1) // Read the differential voltage, (channel 0–1) → (float32 V, error)
		must(err)
		println("CH1 raw", raw, "CH2", v, "V")
		both, err := adc.ReadAll() // Read both channels, () → ([2]float32 V, error)
		must(err)
		println(both[0], both[1])
		time.Sleep(time.Second)
	}
}

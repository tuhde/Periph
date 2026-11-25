//go:build tinygo

// ADS1115 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1115I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	m, err := adcdac.NewADS1115Minimal(conn) // Create ADS1115 driver, (conn) → (*ADS1115Minimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		v, err := m.ReadVoltage(0) // Read voltage, (channel=0) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		println("voltage (mV)", int32(v*1000))
		time.Sleep(1000 * time.Millisecond)
	}
}

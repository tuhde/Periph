//go:build tinygo

// ADS1013 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS1013I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	adc, err := adcdac.NewADS1013Minimal(conn) // Create ADS1013 driver, (conn) → (*ADS1013Minimal, error)
	must(err)

	for i := 0; i < 10; i++ {
		raw, err := adc.ReadRaw() // Read raw code, () → (int16, error) (−2048…2047)
		must(err)
		v, err := adc.ReadVoltage() // Read voltage, () → (float32 V, error)
		must(err)
		println("raw", raw)
		println("voltage (mV)", int32((v)*1000))
		time.Sleep(1000 * time.Millisecond)
	}
}

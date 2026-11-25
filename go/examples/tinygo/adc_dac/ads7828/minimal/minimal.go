//go:build tinygo

// ADS7828 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS7828I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	d, err := adcdac.NewADS7828Minimal(conn, 0) // Create ADS7828 driver, (conn, vref=0 → internal 2.5 V) → (*ADS7828Minimal, error)
	if err != nil {
		panic(err)
	}
	if err := d.Init(); err != nil { // Enable internal reference, () → error
		panic(err)
	}

	for i := 0; i < 10; i++ {
		raw, err := d.ReadRaw(0) // Read CH0 raw code, (channel=0–7) → (uint16, error)
		if err != nil {
			panic(err)
		}
		v0, err := d.ReadVoltage(0) // Read CH0 voltage, (channel=0–7) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		all, err := d.ReadAll() // Read all eight channels, () → ([8]float32 V, error)
		if err != nil {
			panic(err)
		}
		println(raw, int32(v0*1000), int32(all[7]*1000))
		time.Sleep(1000 * time.Millisecond)
	}
}

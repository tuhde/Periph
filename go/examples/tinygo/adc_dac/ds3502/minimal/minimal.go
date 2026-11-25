//go:build tinygo

// DS3502 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Steps the wiper through a few positions (RH = 15 V, RL = 0 V), printing the
// readback and the unloaded output voltage, then sets 6.0 V.
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
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.DS3502I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x28) → *I2CConnection
	defer conn.Close()

	pot, err := adcdac.NewDS3502Minimal(conn, 15.0, 0.0) // Create DS3502 driver, (conn, vHigh=1.0 V, vLow=0.0 V) → (*DS3502Minimal, error)
	must(err)
	for _, code := range []int{0, 32, 64, 96, 127} {
		must(pot.SetWiper(code)) // Set wiper position, (code 0–127) → error
		w, err := pot.GetWiper() // Read wiper position, () → (uint8 0–127, error)
		must(err)
		v, err := pot.GetVoltage() // Read unloaded RW voltage, () → (float32 V, error)
		must(err)
		println(code, w, v)
		time.Sleep(500 * time.Millisecond)
	}
	must(pot.SetVoltage(6.0)) // Set RW output voltage, (volts V) → error
}

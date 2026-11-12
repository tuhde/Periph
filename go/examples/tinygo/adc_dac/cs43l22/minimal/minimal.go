//go:build tinygo

// CS43L22 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Bring up the headphone output and set the volume.
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
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 100 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.CS43L22I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x4A) → *I2CConnection
	defer conn.Close()

	dac, err := adcdac.NewCS43L22Minimal(conn, nil) // Create CS43L22 driver, (conn, resPin=nil) → (*CS43L22Minimal, error)
	must(err)
	must(dac.Init())         // Reset, verify ID, load defaults, () → error
	must(dac.Start())        // Power up the DAC once MCLK is running, () → error
	must(dac.SetVolume(-20)) // Set master volume, (db -102–12 dB) → error
	must(dac.Mute(false))    // Unmute the headphone outputs, (on) → error
	for i := 0; i < 5; i++ {
		v, err := dac.GetVolume() // Read back master volume, () → (float32 dB, error)
		must(err)
		println(v)
		time.Sleep(time.Second)
	}
	must(dac.Stop()) // Power down the DAC, () → error
}

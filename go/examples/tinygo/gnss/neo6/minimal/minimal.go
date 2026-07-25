//go:build tinygo

// NEO-6 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver over the I2C (DDC) transport at address 0x42,
// and prints the current GGA position in a loop. The DDC bus requires
// polling at least once per second to avoid the module's 2-second
// idle timeout dropping buffered output.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gnss"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x42)         // Create I2C transport, (i2c, addr=0x42) → *I2CTransport
	chip := gnss.NewNEO6Minimal(tr, "i2c")            // Create NEO-6 driver, (transport, bus_type="i2c") → *NEO6Minimal

	for {
		_, err := chip.Update() // Read and parse one NMEA sentence, () → (bool, error)
		if err != nil {
			println("update:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fix := chip.Fix() // Get last GGA fix quality, () → uint8
		sats := chip.Satellites() // Get last GGA satellite count, () → uint8
		lat := chip.Latitude() // Get last fix latitude, () → *float64
		lon := chip.Longitude() // Get last fix longitude, () → *float64
		alt := chip.Altitude() // Get last fix altitude, () → *float64
		if fix > 0 && lat != nil && lon != nil {
			fmt.Printf("fix=%d sats=%d lat=%.6f lon=%.6f alt=%.1f\n",
				fix, sats, *lat, *lon, *alt)
		} else {
			fmt.Printf("fix=%d sats=%d (waiting for fix)\n", fix, sats)
		}
		time.Sleep(time.Second)
	}
}

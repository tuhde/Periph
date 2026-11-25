//go:build tinygo

// MAX44009 demo — TinyGo / Raspberry Pi Pico W.
//
// Adaptive backlight controller: arms an interrupt window of +-30 % around the current
// illuminance, maps the light level to a backlight percentage whenever the light
// leaves the window for 2 s, re-centres the window and prints the raw reading once a second.
package main

import (
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, light.MAX44009I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x4A) → *I2CConnection
	defer conn.Close()

	sensor, err := light.NewMAX44009Full(conn) // Create MAX44009 Full driver, (conn) → (*MAX44009Full, error)
	must(err)

	backlight := func(lx float32) float32 {
		if lx < 0.1 {
			lx = 0.1
		}
		if lx > 10000 {
			lx = 10000
		}
		return 10 + 90*(float32(math.Log10(float64(lx)))+1)/5
	}

	cfg, err := sensor.GetConfig() // Read CONFIG, () → (MAX44009Config, error)
	must(err)
	lux, err := sensor.Lux() // Read illuminance, () → (float32 lux, error)
	must(err)
	println("config manual", cfg.Manual, "lux", lux, "resolution", sensor.Resolution(), "lx/LSB")

	// Arm a window of +-30 % around the current illuminance; the callback only records
	// the event, the loop below reacts to it.
	pending := make(chan uint8, 1)
	must(sensor.SetThresholdDelay(2.0))          // Set the threshold delay, (seconds) → error
	must(sensor.SetThresholds(lux*0.7, lux*1.3)) // Set the interrupt window, (lowLux, highLux) → error
	must(sensor.EnableInterrupt())               // Enable the interrupt, () → error
	must(sensor.OnInterrupt(func(status uint8) { // Subscribe, (callback(status)) → error
		select {
		case pending <- status:
		default:
		}
	}))
	println("backlight", backlight(lux), "%")

	// Run for 60 s: react to interrupts, print the raw reading once a second.
	for s := 0; s < 60; s++ {
		select {
		case <-pending:
			now, err := sensor.Lux() // Read illuminance, () → (float32 lux, error)
			must(err)
			println("lux", now, "-> backlight", backlight(now), "%")
			must(sensor.SetThresholds(now*0.7, now*1.3)) // Set the interrupt window, (lowLux, highLux) → error
		default:
		}
		e, m, _, err := sensor.ReadRaw() // Read exponent and mantissa, () → (uint8, uint8, bool, error)
		must(err)
		println("E", e, "M", m, "overrange", sensor.IsOverrange())
		time.Sleep(time.Second)
	}
	must(sensor.DisableInterrupt()) // Disable the interrupt, () → error
	must(sensor.OffInterrupt())     // Unsubscribe, () → error
}

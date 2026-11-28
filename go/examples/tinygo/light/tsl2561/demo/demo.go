//go:build tinygo

// TSL2561 demo — TinyGo / Raspberry Pi Pico W.
//
// Light meter with alarm: prints the ID, takes an auto-ranged lux reading
// every 2 s for 30 s with gain, integration time and both channel counts,
// then sets a ±50 % channel-0 window with persistence 2, reports
// PollInterrupt() while you cover the sensor, releases the interrupt and
// powers the chip down.
package main

import (
	"machine"
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
	conn := connection.NewI2CConnection(machine.I2C1, light.TSL2561I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x39) → *I2CConnection
	defer conn.Close()

	sensor, err := light.NewTSL2561Full(conn) // Create TSL2561 Full driver, (conn) → (*TSL2561Full, error)
	must(err)
	id, err := sensor.ReadID() // Read the ID register, () → (uint8, error)
	must(err)
	println("ID", id)

	/* Light meter: auto-range picks the most sensitive setting that does not saturate. */
	for i := 0; i < 15; i++ {
		lx, err := sensor.AutoRange() // Auto-range and read lux, () → (float32 lux, error)
		must(err)
		ch0, ch1, err := sensor.ReadRaw() // Read both channels, () → (uint16, uint16, error)
		must(err)
		println(lx, "lx  gain", sensor.Gain(), "integration", sensor.IntegrationTime(), "ms  ch0", ch0, "ch1", ch1) // Current settings, () → uint8 / uint16
		time.Sleep(2 * time.Second)
	}

	/* Alarm: a window of ±50 % around the current channel-0 count. */
	ch0, _, err := sensor.ReadRaw()
	must(err)
	high := uint32(ch0) * 3 / 2
	if high > 65535 {
		high = 65535
	}
	must(sensor.SetThresholds(ch0/2, uint16(high))) // Set channel-0 window, (low, high) → error
	must(sensor.ConfigureInterrupt(1, 2))           // Level interrupt, 2 cycles outside, (mode, persist) → error
	println("window", ch0/2, high, "- cover the sensor now")
	for i := 0; i < 10; i++ {
		outside, err := sensor.PollInterrupt() // Check ch0 against the window, () → (bool, error)
		must(err)
		println("outside window:", outside)
		time.Sleep(time.Second)
	}

	must(sensor.ClearInterrupt()) // Release the level interrupt, () → error
	must(sensor.ConfigureInterrupt(0, 0))
	must(sensor.PowerDown())    // Power down, () → error
	on, _ := sensor.IsPowered() // Power state, () → (bool, error)
	println("powered:", on)
}

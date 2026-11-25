//go:build tinygo

// MAX44009 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method of the public API: lux, raw readings, mode and range,
// thresholds, delay and interrupt handling.
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
	conn := connection.NewI2CConnection(machine.I2C1, light.MAX44009I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x4A) → *I2CConnection
	defer conn.Close()

	sensor, err := light.NewMAX44009Full(conn) // Create MAX44009 Full driver, (conn) → (*MAX44009Full, error)
	must(err)
	// writes the reset state, clears INT_STATUS, waits 0.9 s

	lx, err := sensor.Lux() // Read illuminance, () → (float32 lux, error)
	must(err)
	// 2^E × M × 0.045; 188006.4 on overrange
	println("lux", lx)
	e, m, over, err := sensor.ReadRaw() // Read exponent and mantissa, () → (uint8, uint8, bool, error)
	must(err)
	// one consistent sample: LUX_HIGH, LUX_LOW, LUX_HIGH
	println("E", e, "M", m, "overrange", over)
	last := sensor.IsOverrange() // Query the last reading, () → bool
	// true if the last exponent field was 15
	res := sensor.Resolution() // Lux per LSB, () → float32 lux
	// 2^E × 0.045 for the last reading
	println("overrange", last, "resolution", res)

	must(sensor.SetContinuous(true)) // Select measurement mode, (continuous bool) → error
	// true: a new measurement as soon as one finishes
	must(sensor.SetManual(100, false)) // Select manual range, (integrationMs, divideBy8) → error
	// nearest of 800/400/200/100/50/25/12.5/6.25 ms
	cfg, err := sensor.GetConfig() // Read CONFIG, () → (MAX44009Config, error)
	must(err)
	println("config", cfg.Continuous, cfg.Manual, cfg.DivideBy8, cfg.IntegrationMs)
	must(sensor.SetAutomatic()) // Select automatic range, () → error
	// the chip chooses the current divider and integration time
	must(sensor.SetContinuous(false)) // Back to one measurement per 800 ms, (continuous bool) → error

	must(sensor.SetThresholds(50, 500)) // Set the interrupt window, (lowLux, highLux) → error
	// 4 mantissa MSBs: about 1/16 to 1/8 of the value
	lo, hi, err := sensor.GetThresholds() // Read the window, () → (float32, float32, error)
	must(err)
	println("window", lo, hi)
	must(sensor.SetThresholdDelay(2.0)) // Set the threshold delay, (seconds) → error
	// 0–25.5 s in 0.1 s steps
	delay, err := sensor.GetThresholdDelay() // Read the delay, () → (float32 s, error)
	must(err)
	println("delay", delay)

	must(sensor.EnableInterrupt()) // Enable the interrupt, () → error
	// INT_ENABLE = 1
	must(sensor.OnInterrupt(func(status uint8) { println("interrupt", status) })) // Subscribe, (callback(status)) → error
	// falling edge on INT, or a polling goroutine without a pin
	time.Sleep(10 * time.Second)
	status, err := sensor.PollInterrupt() // Read and clear INT_STATUS, () → (uint8, error)
	must(err)
	// bit 0 = SOURCE_THRESHOLD
	println("status", status)
	must(sensor.OffInterrupt())     // Unsubscribe, () → error
	must(sensor.DisableInterrupt()) // Disable the interrupt, () → error
	// INT_ENABLE = 0; also clears a pending status
}

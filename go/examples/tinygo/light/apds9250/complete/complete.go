//go:build tinygo

// APDS-9250 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every APDS9250Full method once.
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
	conn := connection.NewI2CConnection(machine.I2C1, light.APDS9250I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x52) → *I2CConnection
	defer conn.Close()

	sensor, err := light.NewAPDS9250Full(conn) // Create APDS-9250 Full driver, (conn) → (*APDS9250Full, error)
	must(err)

	part, rev, err := sensor.PartID() // Read PART_ID, () → (part, revision uint8, error)
	must(err)                         // 0xB, 0x5 for the APDS-9250
	println("part", part, "revision", rev)
	c, err := sensor.Read() // Read all channels, () → (APDS9250Counts, error)
	must(err)
	println(c.Red, c.Green, c.Blue, c.IR)
	red, err := sensor.ReadRed() // Read the red channel, () → (uint32, error)
	must(err)
	green, err := sensor.ReadGreen() // Read the green / ALS channel, () → (uint32, error)
	must(err)
	blue, err := sensor.ReadBlue() // Read the blue channel, () → (uint32, error)
	must(err)
	ir, err := sensor.ReadIR() // Read the IR channel, () → (uint32, error)
	must(err)
	println(red, green, blue, ir)
	ready, err := sensor.DataReady() // Query LS_DATA, () → (bool, error)
	must(err)                        // clears the status flags on the chip
	println("ready", ready)
	c, err = sensor.ReadChannels() // Block-read without waiting, () → (APDS9250Counts, error)
	must(err)

	must(sensor.SetMode(false)) // Select ALS/IR mode, (colour bool) → error; false: only green and IR
	must(sensor.SetMode(true))  // Back to colour mode, (colour bool) → error
	must(sensor.Disable())      // Standby, () → error
	must(sensor.Enable())       // Activate the sensor, () → error

	must(sensor.SetResolution(16))       // Set the ADC resolution, (bits 13/16/17/18/19/20) → error; restarts the measurement
	must(sensor.SetMeasurementRate(200)) // Set the measurement rate, (ms 25…2000) → error
	must(sensor.SetGain(6))              // Set the gain, (1/3/6/9/18) → error; restarts the measurement
	bits, err := sensor.GetResolution()  // Read the resolution, () → (uint8, error)
	must(err)
	rate, err := sensor.GetMeasurementRate() // Read the rate, () → (uint16 ms, error)
	must(err)
	gain, err := sensor.GetGain() // Read the gain, () → (uint8, error)
	must(err)
	tint, err := sensor.IntegrationTime() // Integration time, () → (float32 s, error)
	must(err)
	println("resolution", bits, "rate", rate, "gain", gain, "integration", tint)
	g, err := sensor.ReadGreen()
	must(err)
	norm, err := sensor.Normalized(g) // Counts per gain-second, (counts) → (float32, error)
	must(err)
	println("normalized", norm)

	must(sensor.SetThresholds(1000, 100000)) // Set the threshold window, (low, high uint32) → error; 20-bit counts
	lo, hi, err := sensor.GetThresholds()    // Read the window, () → (low, high uint32, error)
	must(err)
	println("window", lo, hi)
	must(sensor.SetInterruptChannel(light.APDS9250ChannelGreen)) // Choose the monitored channel, (channel) → error
	must(sensor.SetPersistence(2))                               // Set the persistence, (1–16) → error
	must(sensor.SetVariance(64))                                 // Set the variation threshold, (8…1024, power of two) → error
	must(sensor.EnableInterrupt(light.APDS9250SourceVariation))  // Enable the interrupt, (source) → error
	must(sensor.OnInterrupt(func(status uint8) {                 // Subscribe, (callback func(status uint8)) → error
		println("interrupt", status)
	}))
	time.Sleep(10 * time.Second)
	status, err := sensor.PollInterrupt() // Read and clear MAIN_STATUS, () → (uint8, error); 0x20 power-on, 0x10 interrupt, 0x08 data
	must(err)
	println("poll", status)
	must(sensor.OffInterrupt())     // Unsubscribe, () → error
	must(sensor.DisableInterrupt()) // Disable the interrupt, () → error
	status, err = sensor.Status()   // Raw MAIN_STATUS, () → (uint8, error)
	must(err)
	println("status", status)
	must(sensor.SoftReset()) // Software reset, () → error; all registers to defaults, sensor in standby
}

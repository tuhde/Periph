//go:build linux && !tinygo

// APDS-9250 demo example — Linux host.
//
// Colour sampler with gain/resolution steps and polled interrupts.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, light.APDS9250I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x52) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	sensor, err := light.NewAPDS9250Full(conn) // Create APDS-9250 Full driver, (conn) → (*APDS9250Full, error)
	must(err)

	// --- Colour sampler ---
	// Prints the part ID and settings, samples R/G/B/IR as a normalised colour,
	// compares ALS/IR mode, steps gain and resolution with normalised counts,
	// arms a variation and a threshold interrupt on the green channel (polled
	// through PollInterrupt), then disables the sensor and soft-resets it.
	part, rev, err := sensor.PartID() // Read PART_ID, () → (part, revision uint8, error)
	must(err)
	bits, _ := sensor.GetResolution()
	rate, _ := sensor.GetMeasurementRate()
	gain, _ := sensor.GetGain()
	fmt.Println("part", part, "revision", rev, "resolution", bits, "rate", rate, "gain", gain)
	for i := 0; i < 20; i++ {
		c, err := sensor.Read() // Read the four channels, () → (APDS9250Counts, error)
		must(err)
		top := c.Red
		if c.Green > top {
			top = c.Green
		}
		if c.Blue > top {
			top = c.Blue
		}
		if top == 0 {
			top = 1
		}
		fmt.Println("rgb", uint64(c.Red)*255/uint64(top), uint64(c.Green)*255/uint64(top), uint64(c.Blue)*255/uint64(top), "IR", c.IR)
		time.Sleep(500 * time.Millisecond)
	}

	must(sensor.SetMode(false)) // Select ALS/IR mode, (colour bool) → error
	g, _ := sensor.ReadGreen()
	ir, _ := sensor.ReadIR()
	fmt.Println("ALS/IR green", g, "ir", ir)
	must(sensor.SetMode(true))

	for _, gn := range []uint8{1, 3, 6, 9, 18} {
		must(sensor.SetGain(gn)) // Set the gain, (gain) → error
		time.Sleep(300 * time.Millisecond)
		g, _ := sensor.ReadGreen()
		n, _ := sensor.Normalized(g) // Counts per gain-second, (counts) → (float32, error)
		fmt.Println("gain", gn, "green", g, "normalized", n)
	}
	must(sensor.SetGain(3))
	for _, b := range []uint8{13, 16, 17, 18, 19, 20} {
		must(sensor.SetResolution(b)) // Set the resolution, (bits) → error
		time.Sleep(500 * time.Millisecond)
		g, _ := sensor.ReadGreen()
		n, _ := sensor.Normalized(g)
		fmt.Println(b, "bit full scale", uint32(1)<<b-1, "green", g, "normalized", n)
	}
	must(sensor.SetResolution(18))
	must(sensor.SetMeasurementRate(100)) // Set the rate, (ms) → error

	must(sensor.SetInterruptChannel(light.APDS9250ChannelGreen)) // Choose the channel, (channel) → error
	must(sensor.SetPersistence(2))                               // Set the persistence, (count) → error
	must(sensor.SetVariance(64))                                 // Set the variation threshold, (counts) → error
	must(sensor.EnableInterrupt(light.APDS9250SourceVariation))  // Enable the variation interrupt, (source) → error
	for i := 0; i < 20; i++ {
		time.Sleep(500 * time.Millisecond)
		status, err := sensor.PollInterrupt() // Read and clear MAIN_STATUS, () → (uint8, error)
		must(err)
		if status&light.APDS9250StatusInterrupt != 0 {
			g, _ := sensor.ReadGreen()
			fmt.Println("variation interrupt, green", g)
		}
	}

	base, _ := sensor.ReadGreen()
	must(sensor.SetThresholds(base-base/4, base+base/4))        // Set the window, (low, high) → error
	must(sensor.EnableInterrupt(light.APDS9250SourceThreshold)) // Enable the threshold interrupt, (source) → error
	time.Sleep(5 * time.Second)
	status, _ := sensor.PollInterrupt()
	fmt.Println("status", status)
	must(sensor.DisableInterrupt()) // Disable the interrupt, () → error
	must(sensor.Disable())          // Standby, () → error
	must(sensor.SoftReset())        // Software reset, () → error
}

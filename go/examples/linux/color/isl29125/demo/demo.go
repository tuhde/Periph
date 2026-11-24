//go:build linux && !tinygo

// ISL29125 demo example — Linux host.
//
// Colour sampler: range, resolution and IR-compensation steps and a polled threshold interrupt.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/color"
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

	conn, err := connection.NewI2CConnection(bus, color.ISL29125I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x44) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	sensor, err := color.NewISL29125Full(conn) // Create ISL29125 Full driver, (conn) → (*ISL29125Full, error)
	must(err)

	// --- Colour sampler ---
	// Prints the device ID, samples R/G/B for 10 s as a normalised colour,
	// compares the 375 lux and 10 000 lux ranges and the 12/16-bit resolutions,
	// varies the IR compensation, reads green only, arms a threshold interrupt on
	// green with persistence 2 (polled through PollInterrupt), then powers down
	// and resets.
	id, err := sensor.DeviceID() // Read DEVICE_ID, () → (uint8, error)
	must(err)
	fmt.Println("device id", id)
	for i := 0; i < 20; i++ {
		c, err := sensor.Read() // Read the three channels, () → (ISL29125Counts{Red, Green, Blue}, error)
		must(err)
		top := uint32(c.Red)
		if uint32(c.Green) > top {
			top = uint32(c.Green)
		}
		if uint32(c.Blue) > top {
			top = uint32(c.Blue)
		}
		if top == 0 {
			top = 1
		}
		fmt.Println("rgb", uint32(c.Red)*255/top, uint32(c.Green)*255/top, uint32(c.Blue)*255/top, "counts", c.Red, c.Green, c.Blue)
		time.Sleep(500 * time.Millisecond)
	}
	for _, lux := range []uint16{375, 10000} {
		must(sensor.SetRange(lux)) // Set the range, (lux) → error
		time.Sleep(400 * time.Millisecond)
		c, err := sensor.ReadRGB() // Read without waiting, () → (ISL29125Counts, error)
		must(err)
		fmt.Println(lux, "lux range", c.Red, c.Green, c.Blue)
	}
	for _, bits := range []uint8{12, 16} {
		must(sensor.SetResolution(bits)) // Set the resolution, (bits) → error
		time.Sleep(400 * time.Millisecond)
		c, err := sensor.ReadRGB()
		must(err)
		fmt.Println(bits, "bit full scale", (uint32(1)<<bits)-1, c.Red, c.Green, c.Blue)
	}
	must(sensor.SetRange(10000))
	for _, adjust := range []uint8{0, 0x20, 0x3F} {
		must(sensor.SetIRCompensation(false, adjust)) // Set the IR compensation, (offset, adjust) → error
		time.Sleep(400 * time.Millisecond)
		c, err := sensor.ReadRGB()
		must(err)
		fmt.Println("IR compensation", adjust, c.Red, c.Green, c.Blue)
	}
	must(sensor.SetIRCompensation(true, 0x3F))
	must(sensor.SetMode(color.ISL29125ModeGreen)) // Green only, (mode) → error
	time.Sleep(300 * time.Millisecond)
	g, err := sensor.ReadGreen() // Read green, () → (uint16, error)
	must(err)
	fmt.Println("green only", g)
	must(sensor.SetMode(color.ISL29125ModeRGB))
	ready, err := sensor.DataReady() // Query CONVENF, () → (bool, error)
	must(err)
	fmt.Println("ready", ready)

	base, err := sensor.ReadGreen()
	must(err)
	low, high := base-base/4, base+base/4
	if high < base {
		high = 0xFFFF
	}
	must(sensor.SetThresholds(low, high)) // Set the window, (low, high) → error
	lo, hi, err := sensor.GetThresholds() // Read the window, () → (low, high uint16, error)
	must(err)
	fmt.Println("window", lo, "-", hi)
	must(sensor.SetInterruptChannel(color.ISL29125ChannelGreen)) // Choose the channel, (channel) → error
	must(sensor.SetPersistence(2))                               // Set the persistence, (count) → error
	must(sensor.EnableInterrupt(color.ISL29125SourceThreshold))  // Enable the interrupt, (source) → error
	for i := 0; i < 20; i++ {
		time.Sleep(500 * time.Millisecond)
		st, err := sensor.PollInterrupt() // Read and clear STATUS, () → (uint8, error)
		must(err)
		if st&color.ISL29125StatusThreshold != 0 {
			v, err := sensor.ReadGreen()
			must(err)
			fmt.Println("window crossed, green", v)
		}
	}
	must(sensor.DisableInterrupt(color.ISL29125SourceThreshold)) // Disable the interrupt, (source) → error
	st, err := sensor.Status()                                   // Raw STATUS, () → (uint8, error)
	must(err)
	fmt.Println("status", st)
	must(sensor.SetMode(color.ISL29125ModePowerDown)) // Power down, (mode) → error
	must(sensor.SoftReset())                          // Software reset, () → error
}

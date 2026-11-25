//go:build linux && !tinygo

// ISL29125 complete example — Linux host.
//
// Exercises every ISL29125Full method once.
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

	id, err := sensor.DeviceID() // Read DEVICE_ID, () → (uint8, error)
	must(err)                    // 0x7D for the ISL29125
	fmt.Println("device id", id)
	c, err := sensor.Read() // Read the three channels, () → (ISL29125Counts{Red, Green, Blue}, error)
	must(err)
	fmt.Println("R", c.Red, "G", c.Green, "B", c.Blue)
	r, err := sensor.ReadRed() // Read the red channel, () → (uint16, error)
	must(err)
	g, err := sensor.ReadGreen() // Read the green channel, () → (uint16, error)
	must(err)
	b, err := sensor.ReadBlue() // Read the blue channel, () → (uint16, error)
	must(err)
	fmt.Println("red", r, "green", g, "blue", b)
	c, err = sensor.ReadRGB() // Block-read without waiting, () → (ISL29125Counts, error)
	must(err)
	fmt.Println("rgb", c.Red, c.Green, c.Blue)
	ready, err := sensor.DataReady() // Query CONVENF, () → (bool, error)
	must(err)                        // clears the status flags on the chip
	fmt.Println("ready", ready)
	must(sensor.SetMode(color.ISL29125ModeGreen)) // Select the channels, (mode) → error; ISL29125ModePowerDown … ISL29125ModeGreenBlue
	must(sensor.SetMode(color.ISL29125ModeRGB))
	must(sensor.SetRange(375)) // Set the full-scale range, (lux) → error; 375 or 10000
	must(sensor.SetRange(10000))
	must(sensor.SetResolution(12)) // Set the ADC resolution, (bits) → error; 12 or 16
	must(sensor.SetResolution(16))
	must(sensor.SetSync(false))                // Select the conversion start, (external) → error; true: rising edge of INT starts the conversion
	must(sensor.SetIRCompensation(true, 0x3F)) // Set the IR compensation, (offset, adjust) → error; adjust 0-63, offset selects codes 106-169
	must(sensor.SetThresholds(1000, 50000))    // Set the threshold window, (low, high) → error
	lo, hi, err := sensor.GetThresholds()      // Read the window, () → (low, high uint16, error)
	must(err)
	fmt.Println("window", lo, "-", hi)
	must(sensor.SetInterruptChannel(color.ISL29125ChannelGreen))                      // Choose the monitored channel, (channel) → error; ISL29125ChannelNone, Green, Red or Blue
	must(sensor.SetPersistence(2))                                                    // Set the persistence, (count) → error; 1, 2, 4 or 8 integration cycles
	must(sensor.EnableInterrupt(color.ISL29125SourceThreshold))                       // Enable the interrupt, (source) → error; ISL29125SourceThreshold or ISL29125SourceConversion
	must(sensor.OnInterrupt(func(status uint8) { fmt.Println("interrupt", status) })) // Subscribe, (callback) → error
	time.Sleep(2 * time.Second)
	must(sensor.OffInterrupt())       // Unsubscribe, () → error
	st, err := sensor.PollInterrupt() // Read and clear STATUS, () → (uint8, error)
	must(err)                         // 0x04 brown-out, 0x02 conversion, 0x01 threshold
	fmt.Println("poll", st)
	must(sensor.DisableInterrupt(color.ISL29125SourceThreshold)) // Disable the interrupt, (source) → error
	st, err = sensor.Status()                                    // Raw STATUS, () → (uint8, error)
	must(err)
	fmt.Println("status", st)
	must(sensor.SetMode(color.ISL29125ModePowerDown))
	must(sensor.SoftReset()) // Software reset, () → error; all registers to defaults
}

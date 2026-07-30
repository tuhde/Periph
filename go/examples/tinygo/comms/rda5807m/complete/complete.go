//go:build tinygo

// RDA5807M complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises the RDA5807MFull API on a Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x10, nil, nil)          // Create I2C connection, (i2c, addr=0x10) → (*I2CConnection)
	fm, err := comms.NewRDA5807MFull(conn, 100.0, 8)      // Create RDA5807M driver, (connection, frequency_mhz=100.0, volume=8) → (*RDA5807MFull, error)
	if err != nil {
		panic(err)
	}

	if err := fm.SetFrequency(97.5); err != nil { // Tune to frequency, (frequency_mhz) → error
		panic(err)
	}
	f, err := fm.Frequency() // Read tuned frequency, () → (float64 MHz, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("frequency: %.2f MHz\n", f)
	if err := fm.SetVolume(10); err != nil { // Set volume, (level 0–15) → error
		panic(err)
	}
	if err := fm.Mute(false); err != nil { // Mute/unmute, (enable) → error
		panic(err)
	}
	if err := fm.EnableRds(true); err != nil { // Enable RDS/RBDS, (enable) → error
		panic(err)
	}
	time.Sleep(time.Second)
	rr, err := fm.RdsReady() // Check RDS group ready, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("rds_ready:", rr)
	st, err := fm.IsStereo() // Check stereo indicator, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("is_stereo:", st)
	ready, err := fm.IsReady() // Check tuner ready, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("is_ready:", ready)
	rssi, err := fm.SignalStrength() // Read RSSI, () → (uint8 0–127, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("signal_strength: %d\n", rssi)
}

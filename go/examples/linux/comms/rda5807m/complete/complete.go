//go:build linux && !tinygo

// RDA5807M complete example — Linux host.
//
// Exercises every method in the Rda5807mFull API: tune, frequency,
// volume, mute, seek, configure, bass boost, mono, soft mute, RDS,
// status flags, RSSI, standby, and soft reset.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x10"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x10) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	fm, err := comms.NewRda5807mFull(conn, 100.0, 8) // Create RDA5807M driver, (connection, frequency_mhz=100.0, volume=8) → (*Rda5807mFull, error)
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

	if freq, err := fm.Seek(true); err != nil { // Seek next station, (up=true) → (*float64 MHz, error)
		panic(err)
	} else if freq != nil {
		fmt.Printf("seek found: %.2f MHz\n", *freq)
	}

	band := comms.BandWorld
	space := comms.Space100K
	de := true
	sm := true
	th := uint8(8)
	if err := fm.Configure(&band, &space, &de, &sm, nil, nil, &th, nil); err != nil { // Configure tuner, (band, space, de_emphasis, seek_mode, afc_disable, east_europe_50m, seek_threshold, clk_mode) → error
		panic(err)
	}

	if err := fm.SetBassBoost(true); err != nil { // Enable bass boost, (enable) → error
		panic(err)
	}
	if err := fm.SetMono(false); err != nil { // Force mono/allow stereo, (enable) → error
		panic(err)
	}
	if err := fm.SetSoftmute(true); err != nil { // Enable soft mute, (enable) → error
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
	if group, err := fm.ReadRdsGroup(); err != nil { // Read raw RDS blocks, () → (*[4]uint16, error)
		panic(err)
	} else if group != nil {
		fmt.Printf("rds group: %v\n", *group)
	}

	st, err := fm.IsStereo() // Check stereo indicator, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("is_stereo:", st)
	station, err := fm.IsStation() // Check real station, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("is_station:", station)
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

	if err := fm.Standby(true); err != nil { // Power down/up, (enable) → error
		panic(err)
	}
	time.Sleep(10 * time.Millisecond)
	if err := fm.Standby(false); err != nil {
		panic(err)
	}

	if err := fm.SoftReset(); err != nil { // Pulse soft reset, () → error
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

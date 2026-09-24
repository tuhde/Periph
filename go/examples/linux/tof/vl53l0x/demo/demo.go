//go:build linux && !tinygo

// VL53L0X demo — Linux host. Touchless presence gate with multi-rate
// ranging: a first measurement picks the profile (long range in a dark room,
// default otherwise), then timed continuous ranging at 100 ms feeds an
// out-of-window interrupt — closer than 10 cm is an ENTER event, the scene
// clearing beyond 80 cm a LEAVE event. After 20 events or 60 s, it prints
// statistics over 10 fresh samples, stops ranging and recalibrates. Without a
// GPIO1 pin on the connection the driver's polling goroutine delivers the
// events.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/tof"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	maxEvents = 20
	maxTime   = 60 * time.Second
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	conn, err := connection.NewI2CConnection(bus, tof.VL53L0XI2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x29) → (*I2CConnection, error)
	must(err)
	defer conn.Close()
	s, err := tof.NewVL53L0XFull(conn) // Create VL53L0X Full driver, (conn) → (*VL53L0XFull, error)
	must(err)

	// --- Pick a profile from the ambient light level ---
	// The long-range profile (0.1 MCPS limit, 18/14 PCLK VCSEL periods) reaches
	// ~2 m, but only without IR background; in daylight it mostly adds invalid
	// readings. One single-shot measurement tells us how bright the scene is.
	_, err = s.Distance() // Measure distance, () → (uint16 mm, error)
	must(err)
	first, err := s.ReadMeasurement() // Read result block, () → (VL53L0XMeasurement, error)
	must(err)
	if first.AmbientRateMCPS < 0.5 {
		must(s.SetProfile(tof.VL53L0XProfileLongRange)) // Apply ranging profile, (profile) → error
		fmt.Printf("dark scene (%.2f MCPS ambient): long range profile\n", first.AmbientRateMCPS)
	} else {
		must(s.SetProfile(tof.VL53L0XProfileDefault)) // Apply ranging profile, (profile) → error
		fmt.Printf("bright scene (%.2f MCPS ambient): default profile\n", first.AmbientRateMCPS)
	}

	// --- Arm the presence gate ---
	// Timed ranging every 100 ms keeps the laser mostly idle. The firmware
	// compares each result with the 100 mm / 800 mm window itself and only
	// raises GPIO1 when a reading falls outside it.
	must(s.SetInterruptThresholds(100, 800))              // Set distance thresholds, (lowMm mm, highMm mm) → error
	must(s.EnableInterrupt(tof.VL53L0XSourceOutOfWindow)) // Select interrupt source, (source) → error
	must(s.StartContinuous(100))                          // Start continuous ranging, (periodMs=0 ms) → error

	// --- Classify each event ---
	// The status is already cleared; the result block still holds the
	// measurement that triggered it.
	events := make(chan uint16, maxEvents)
	must(s.OnInterrupt(func(uint8) { // Subscribe to GPIO1, (callback) → error
		if m, err := s.ReadMeasurement(); err == nil { // Read result block, () → (VL53L0XMeasurement, error)
			select {
			case events <- m.DistanceMM:
			default:
			}
		}
	}))
	deadline := time.After(maxTime)
loop:
	for n := 0; n < maxEvents; n++ {
		select {
		case d := <-events:
			if d < 100 {
				fmt.Println("ENTER", d, "mm")
			} else {
				fmt.Println("LEAVE", d, "mm")
			}
		case <-deadline:
			break loop
		}
	}

	// --- Statistics over fresh samples ---
	// Threshold sources hide ordinary samples from DataReady, so switch back
	// to new-sample-ready before using the blocking continuous reads.
	must(s.OffInterrupt())                                   // Unsubscribe, () → error
	must(s.EnableInterrupt(tof.VL53L0XSourceNewSampleReady)) // Select interrupt source, (source) → error
	_, _ = s.PollInterrupt()                                 // Read and clear status, () → (uint8, error)
	var sum, rate float32
	lo, hi := uint16(0xFFFF), uint16(0)
	for i := 0; i < 10; i++ {
		d, err := s.ReadContinuous() // Read next continuous result, () → (uint16 mm, error)
		must(err)
		m, _ := s.ReadMeasurement() // Read result block, () → (VL53L0XMeasurement, error)
		rate += m.SignalRateMCPS
		sum += float32(d)
		lo, hi = min(lo, d), max(hi, d)
	}
	fmt.Printf("mean %.0f mm, min %d mm, max %d mm, signal %.2f MCPS\n", sum/10, lo, hi, rate/10)

	// --- Shut down and recalibrate ---
	// Reference calibration must run in software standby. Repeat it whenever
	// the sensor's temperature has drifted more than 8 °C.
	must(s.StopContinuous()) // Stop continuous ranging, () → error
	time.Sleep(200 * time.Millisecond)
	must(s.Recalibrate()) // Rerun reference calibration, () → error
	fmt.Println("done")
}

//go:build linux && !tinygo

// ADXL362 demo example — Linux host.
//
// Autonomous motion-activated wake: configures referenced activity/inactivity
// thresholds with loop mode, maps AWAKE to INT2, enters wake-up mode, and
// polls awake() every 200 ms for 60 s, counting asleep↔awake transitions.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewSPIConnection(bus, device, 0, 8_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, max_speed=8 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := accelerometer.NewADXL362Full(conn) // Create ADXL362 Full driver, (connection) → (*ADXL362Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure referenced activity/inactivity thresholds ---
	chip.SetActivityThreshold(0.25, true)                                       // Set activity threshold, (thresholdG=0.25, referenced=true) → error
	chip.SetInactivityThreshold(0.15, true)                                    // Set inactivity threshold, (thresholdG=0.15, referenced=true) → error
	chip.SetInactivityTime(30)                                                 // Set inactivity time, (samples=30) → error

	// --- Engage linked/loop mode and enable both detectors ---
	chip.EnableActivityDetection(true)                                          // Enable activity detection, (enabled=true) → error
	chip.EnableInactivityDetection(true)                                        // Enable inactivity detection, (enabled=true) → error
	chip.SetLinkLoopMode(accelerometer.ADXL362LinkLoopLoop)                    // Set link/loop mode, (mode=LOOP=3) → error

	// --- Map AWAKE to INT2 and enter wake-up mode ---
	chip.SetInterrupt(2, accelerometer.ADXL362SourceAwake, true)               // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → error
	chip.SetWakeupMode(true)                                                    // Enter wake-up mode, (enabled=true) → error

	// --- Poll AWAKE for 60 s and count asleep↔awake transitions ---
	fmt.Println("Watching for motion. Pick up or tap the board to wake; " +
		"let it settle to sleep.")
	start := time.Now()
	lastAwake := (*bool)(nil)
	transitions := 0
	for time.Since(start) < 60*time.Second {                                   // Loop until 60 s elapsed, () → bool
		nowAwake, _ := chip.Awake()                                             // Read AWAKE bit, () → (bool, error)
		if lastAwake == nil || *lastAwake != nowAwake {
			fmt.Printf("%6.2fs  %s\n", time.Since(start).Seconds(),
				stateLabel(nowAwake))                                          // Print timestamped state, () → None
			transitions++
			b := nowAwake
			lastAwake = &b
		}
		time.Sleep(200 * time.Millisecond)                                      // Sleep 200 ms between polls, () → None
	}

	fmt.Printf("Total transitions observed: %d\n", transitions)               // Print final count, () → None
	fmt.Println("Note: during 'asleep' periods the ADXL362 draws ~270 nA — " +
		"roughly two orders of magnitude below the ~1.8 µA of the " +
		"continuous 100 Hz measurement mode used by the Minimal " +
		"read() example.")
}

func stateLabel(awake bool) string {
	if awake {
		return "AWAKE"
	}
	return "asleep"
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
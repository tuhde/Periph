//go:build tinygo

// ADXL362 demo example — TinyGo / Raspberry Pi Pico W.
//
// Autonomous motion-activated wake: configures referenced activity/inactivity
// thresholds with loop mode, maps AWAKE to INT2, enters wake-up mode, and
// polls awake() every 200 ms, counting asleep↔awake transitions.
package main

import (
	"machine"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 8_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	chip, err := accelerometer.NewADXL362Full(conn)                 // Create ADXL362 Full driver, (connection) → (*ADXL362Full, error)
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

	// --- Poll AWAKE indefinitely and count asleep↔awake transitions ---
	println("Watching for motion. Pick up or tap the board to wake; "
		+ "let it settle to sleep.")
	start := time.Now()
	lastAwake := false
	haveLast := false
	transitions := 0
	for {
		nowAwake, _ := chip.Awake()                                             // Read AWAKE bit, () → (bool, error)
		if !haveLast || nowAwake != lastAwake {
			state := "asleep"
			if nowAwake {
				state = "AWAKE"
			}
			println(strconv.FormatFloat(time.Since(start).Seconds(), 'f', 2, 32),
				"s", " ", state)                                               // Print timestamped state, () → None
			transitions++
			lastAwake = nowAwake
			haveLast = true
		}
		time.Sleep(200 * time.Millisecond)                                       // Sleep 200 ms between polls, () → None
	}
}
//go:build linux && !tinygo

// LIS3DH demo example — Linux host.
//
// Tilt, double-tap and free-fall monitor: the chip streams its samples
// through the FIFO while two gesture detectors are armed. Every 100 ms the
// FIFO is drained and averaged, the tilt is printed, and the gesture
// detectors are polled.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	// runDuration is how long the monitor runs before it exits.
	runDuration = 60 * time.Second
	// samplePeriod is the interval between two FIFO drains.
	samplePeriod = 100 * time.Millisecond
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x18"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x18) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	chip, err := accelerometer.NewLIS3DHFull(conn, uint8(addr)) // Create LIS3DH Full driver, (connection, addr=0x18) → (*LIS3DHFull, error)
	must(err)

	// --- Configure the tilt sensor ---
	// ±2 g with 1 mg/LSB in the 12-bit high-resolution mode gives the
	// finest resolution; 50 Hz halves the I²C traffic compared to the
	// 100 Hz default and is still fast enough for a tilt display.
	must(chip.SetRange(accelerometer.LIS3DHRange2G))           // Set measurement range, (rangeG=2|4|8|16) → error
	must(chip.SetDataRate(50))                                 // Set output data rate, (rateHz=0|1|10|25|50|100|200|400|1344|1620|5376) → error
	must(chip.SetMode(accelerometer.LIS3DHModeHighResolution)) // Set operating mode, (mode=0 low-power|1 normal|2 high-resolution) → error

	// --- Verify the transducer with the electrostatic self-test ---
	// The self-test excites the springs and checks that every axis shows
	// the expected displacement; it is a one-off check at start-up.
	ok, err := chip.SelfTest(1) // Run the self-test, (mode=1|2) → (bool, error)
	must(err)
	fmt.Printf("self-test: %v\n", ok)

	// --- Arm the double-tap and the free-fall detector ---
	// A double tap on Z at 0.8 g, and free-fall below 0.35 g for 60 ms.
	// The generator interrupt is latched so a short free-fall is not lost
	// between two polls.
	must(chip.SetClick(0, 0x04, 0.8, 20, 40, 200, true))     // Configure click detection, (single, double, thresholdG, timeLimitMs, latencyMs, windowMs, latch) → error
	must(chip.SetFreeFall(1, 0.35, 60))                      // Configure free-fall, (generator, thresholdG, durationMs) → error
	must(chip.SetLatch(1, true))                             // Latch the generator interrupt, (generator, enabled) → error
	must(chip.EnableInterrupt(accelerometer.SourceClick, 1)) // Route the click source to INT1, (source, pin=1|2) → error
	must(chip.EnableInterrupt(accelerometer.SourceIA1, 1))   // Route the generator source to INT1, (source, pin=1|2) → error

	// --- Stream the samples through the FIFO ---
	// Stream mode keeps the 32 newest samples; the watermark raises an
	// interrupt once 16 samples are buffered.
	must(chip.SetFIFO(2, 16, false)) // Configure the FIFO, (mode=0|1|2|3, watermark=0..31, triggerInt2) → error

	// --- Monitor for one minute ---
	// The FIFO is drained every 100 ms and averaged, which smooths the
	// 50 Hz conversion noise. The tilt angles assume a device flat on a
	// table with Z pointing up.
	deadline := time.Now().Add(runDuration)
	for time.Now().Before(deadline) {
		level, err := chip.FIFOLevel()   // Read the FIFO level, () → (int, error)
		must(err)                        // the number of buffered sample sets
		samples, err := chip.ReadFIFO(0) // Drain the FIFO, (count=0 reads all) → ([]Sample, error)
		must(err)                        // one 6-byte burst per sample set
		if len(samples) > 0 {
			var sumX, sumY, sumZ float64
			for _, s := range samples {
				sumX += s.X
				sumY += s.Y
				sumZ += s.Z
			}
			n := float64(len(samples))
			x, y, z := sumX/n, sumY/n, sumZ/n
			pitch := math.Atan2(-x, math.Sqrt(y*y+z*z)) * 180 / math.Pi
			roll := math.Atan2(y, math.Sqrt(x*x+z*z)) * 180 / math.Pi
			magnitude := math.Sqrt(x*x + y*y + z*z)
			temp, err := chip.ReadTemperatureDelta() // Read the relative temperature, () → (int °C, error)
			must(err)                                // relative delta, 1 digit/°C
			fmt.Printf("fifo=%2d pitch=%6.1f° roll=%6.1f° |g|=%.4f temp=%3d °C\n",
				level, pitch, roll, magnitude, temp)
		}
		status, err := chip.PollInterrupt() // Poll and clear the interrupt status, () → (uint16, error)
		must(err)                           // reading the source registers clears their latched flags
		if status&accelerometer.SourceClick != 0 {
			fmt.Println("DOUBLE TAP")
		}
		if status&accelerometer.SourceIA1 != 0 {
			fmt.Println("FREE FALL")
		}
		time.Sleep(samplePeriod)
	}
}

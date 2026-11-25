//go:build linux && !tinygo

// FXOS8700 demo example — Linux host.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1E"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x1E) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	chip, err := accelerometer.NewFXOS8700Full(conn) // Create FXOS8700 Full driver, (connection) → (*FXOS8700Full, error)
	must(err)

	directions := []string{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"}
	orientations := []string{"PORTRAIT UP", "PORTRAIT DOWN", "LANDSCAPE RIGHT", "LANDSCAPE LEFT"}
	const declination = 3.0 // magnetic declination of the site in degrees

	// --- Configure hybrid mode ---
	// ±4 g keeps tilt maths precise while tolerating handling; magnetometer
	// oversampling ratio 7 gives the lowest noise at this data rate.
	must(chip.SetRange(4))               // Set accelerometer range, (rangeG=2|4|8) → error
	must(chip.SetMagOversampling(7, -1)) // Set magnetometer oversampling, (ratio=0..7, sleepRatio=-1) → error

	// --- Check the accelerometer ---
	// The electrostatic self-test must move every axis by the datasheet minimum.
	ok, dx, dy, dz, err := chip.SelfTest() // Run accelerometer self-test, () → (bool, int, int, int, error) counts
	must(err)
	fmt.Println("self-test", ok, dx, dy, dz)

	// --- Calibrate the hard-iron offsets ---
	// Auto-calibration writes the midpoint of the tracked min/max to the
	// offset registers every cycle; rotate the board through all
	// orientations meanwhile.
	must(chip.SetMagAutoCalibration(true)) // Hardware hard-iron calibration, (enabled) → error
	fmt.Println("Rotate the board in all directions for 15 s ...")
	for s := 0; s < 15; s++ {
		time.Sleep(time.Second)
		minUt, maxUt, err := chip.ReadMagMinMax() // Read tracked min / max, () → ([3]float32 µT, [3]float32 µT, error)
		must(err)
		fmt.Printf("span %.1f %.1f %.1f uT\n", maxUt[0]-minUt[0], maxUt[1]-minUt[1], maxUt[2]-minUt[2])
	}
	must(chip.SetMagAutoCalibration(false)) // Hardware hard-iron calibration, (enabled) → error
	ox, oy, oz, err := chip.GetMagOffset()  // Get hard-iron offsets, () → (float32 µT, float32 µT, float32 µT, error)
	must(err)
	fmt.Println("offsets", ox, oy, oz)

	// --- Arm orientation and motion detection ---
	// Orientation changes and motion are routed to INT1; Auto-SLEEP drops the
	// chip to a low rate after 10 s without motion, and motion wakes it again.
	must(chip.SetOrientation(true, 100, 45, 4, 4, 2))                                                       // Configure orientation detection, (enabled, debounceMs, thresholdDeg, hysteresis, zLock, backFront) → error
	must(chip.SetFreefallMotion(true, 7, 0.3, 60, true, false))                                             // Configure freefall / motion, (motion, axes, thresholdG, debounceMs, latch, debounceClear) → error
	must(chip.SetAutoSleep(true, 12.5, 10, accelerometer.FXOS8700SourceFfMt))                               // Configure Auto-SLEEP, (enabled, sleepRateHz, timeoutS, wakeSources) → error
	must(chip.EnableInterrupt(accelerometer.FXOS8700SourceOrientation|accelerometer.FXOS8700SourceFfMt, 1)) // Enable and route interrupt, (source mask, pin=1|2) → error

	// --- Print the heading ---
	// Every 250 ms the tilt-compensated heading is shown with the board's
	// pitch and roll; the temperature is printed once per second.
	for tick := 0; tick < 240; tick++ {
		heading, err := chip.Heading(declination) // Compute tilt-compensated heading, (declinationDeg) → (float32 degrees, error)
		must(err)
		accel, _, err := chip.ReadHybrid() // Read both sensors, () → ([3]float32 g, [3]float32 µT, error)
		must(err)
		roll := math.Atan2(float64(accel[1]), float64(accel[2])) * 180 / math.Pi
		pitch := math.Atan2(float64(-accel[0]), math.Hypot(float64(accel[1]), float64(accel[2]))) * 180 / math.Pi
		fmt.Printf("%5.1f deg %-3s pitch %5.1f roll %5.1f\n", heading, directions[int((heading+11.25)/22.5)%16], pitch, roll)
		status, err := chip.PollInterrupt() // Poll and clear interrupt sources, () → (uint8, error)
		must(err)
		if status&accelerometer.FXOS8700SourceOrientation != 0 {
			_, _, pl, _, err := chip.ReadOrientation() // Read orientation, () → (changed, lockout bool, portraitLandscape uint8, back bool, error)
			must(err)
			fmt.Println(orientations[pl])
		}
		if status&accelerometer.FXOS8700SourceFfMt != 0 {
			mode, err := chip.ReadSystemMode() // Read SYSMOD, () → (uint8, error) 0 standby | 1 wake | 2 sleep
			must(err)
			fmt.Println("MOTION, system mode", mode)
		}
		if tick%4 == 0 {
			t, err := chip.ReadTemperature() // Read die temperature, () → (float32 °C, error)
			must(err)
			fmt.Printf("temperature %.1f C\n", t)
		}
		time.Sleep(250 * time.Millisecond)
	}
}

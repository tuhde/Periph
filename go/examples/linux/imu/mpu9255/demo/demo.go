//go:build linux && !tinygo

// MPU9255 demo example — Linux host.
//
// "Motion-triggered wake logger": configures Full with wake-on-motion
// at a 64 mg threshold and 31.25 Hz wake-up rate, then puts the chip
// into accelerometer-only cycle mode. While idle, polls motion_detected
// in a loop with a short sleep; emits a "sleeping…" heartbeat at ~1 Hz.
// When motion is detected, re-enables the gyroscope and magnetometer,
// captures a 5-second tilt/heading burst at ~10 Hz, then returns to
// low-power wake-on-motion mode.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	magFactory := func(a uint8) (connection.Connection, error) {
		return connection.NewI2CConnection(bus, a, nil, nil) // Open second I2C fd for the AK8963, (bus, addr) → (*I2CConnection, error)
	}

	chip, err := imu.NewMPU9255Full(conn, magFactory) // Create MPU9255 driver with magnetometer, (connection, magFactory) → (*MPU9255Full, error)
	if err != nil {
		panic(err)
	}
	defer chip.Close()

	// --- Configure for motion-triggered wake logger ---
	// 64 mg threshold and 31.25 Hz wake-up rate balance sensitivity against
	// spurious wake-ups from vibration; once motion fires, the full 6-axis
	// sensor suite (gyro + mag at 100 Hz) is re-enabled to capture a 5-second
	// tilt/heading burst.
	if err := chip.ConfigureWakeOnMotion(64, 31.25); err != nil { // Arm wake-on-motion, (threshold_mg=64, odr_hz=31.25) → error
		panic(err)
	}

	lastHeartbeat := time.Now()
	for {
		// --- Idle phase: motion poll at ~5 Hz, "sleeping…" heartbeat at ~1 Hz ---
		for !func() bool {
			motion, err := chip.MotionDetected() // Check motion interrupt, () → (bool, error)
			if err != nil {
				panic(err)
			}
			return motion
		}() {
			if time.Since(lastHeartbeat) >= time.Second {
				fmt.Println("sleeping...")
				lastHeartbeat = time.Now()
			}
			time.Sleep(200 * time.Millisecond)
		}

		// --- Wake phase: re-arm the full 6-axis + mag stack ---
		// SetSleep(false) clears CYCLE; ConfigureGyro re-enables the gyro axes.
		if err := chip.SetSleep(false); err != nil { // Set/clear sleep, (sleep=true|false) → error
			panic(err)
		}
		if err := chip.ConfigureGyro(1); err != nil { // Set gyroscope full-scale, (full_scale=0–3) → error
			panic(err)
		}
		if err := chip.ConfigureAccel(1); err != nil { // Set accelerometer full-scale, (full_scale=0–3) → error
			panic(err)
		}
		if err := chip.EnableMag(16, 6); err != nil { // Initialise magnetometer, (bits=14|16, mode=1|2|6) → error
			panic(err)
		}

		// --- Capture a 5-second tilt/heading burst at ~10 Hz ---
		// Roll/pitch from gravity (quasi-static) + heading from mag (no tilt comp).
		fmt.Println("--- motion detected ---")
		end := time.Now().Add(5 * time.Second)
		for time.Now().Before(end) {
			for {
				ready, err := chip.DataReady() // Check data-ready interrupt, () → (bool, error)
				if err != nil {
					panic(err)
				}
				if ready {
					break
				}
			}

			ax, ay, az, err := chip.Accel() // Read 3-axis acceleration, () → (float32 m/s², float32 m/s², float32 m/s², error)
			if err != nil {
				panic(err)
			}
			gx, gy, gz, err := chip.Gyro() // Read 3-axis angular rate, () → (float32 rad/s, float32 rad/s, float32 rad/s, error)
			if err != nil {
				panic(err)
			}
			mx, my, _, err := chip.Mag() // Read magnetic field, () → (float32 µT, float32 µT, float32 µT, error)
			if err != nil {
				panic(err)
			}

			axF, ayF, azF := float64(ax), float64(ay), float64(az)
			roll := math.Atan2(ayF, azF) * 180.0 / math.Pi
			pitch := math.Atan2(-axF, math.Sqrt(ayF*ayF+azF*azF)) * 180.0 / math.Pi
			heading := math.Atan2(float64(my), float64(mx)) * 180.0 / math.Pi
			wMag := math.Sqrt(float64(gx)*float64(gx) +
				float64(gy)*float64(gy) +
				float64(gz)*float64(gz))

			fmt.Printf("roll=%6.1f  pitch=%6.1f  heading=%6.1f  |g|=%5.2f\n", roll, pitch, heading, wMag)
			time.Sleep(100 * time.Millisecond)
		}

		// --- Return to low-power wake-on-motion mode ---
		if err := chip.ConfigureWakeOnMotion(64, 31.25); err != nil { // Arm wake-on-motion, (threshold_mg=64, odr_hz=31.25) → error
			panic(err)
		}
		lastHeartbeat = time.Now()
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
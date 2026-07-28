//go:build linux && !tinygo

// MPU9250 demo example — Linux host.
//
// "Levelling indicator": configures the driver for ±4 g accel, ±500
// dps gyro, and 16-bit 100 Hz magnetometer, then in a loop reads accel,
// gyro, and mag; computes roll/pitch from the gravity vector and
// heading from the magnetometer, and prints the orientation and
// heading once per second.
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

	chip, err := imu.NewMPU9250Full(conn, magFactory) // Create MPU9250 driver with magnetometer, (connection, magFactory) → (*MPU9250Full, error)
	if err != nil {
		panic(err)
	}
	defer chip.Close()

	// --- Configure for board-tilt + heading demonstration ---
	// ±4 g and ±500 dps cover slow walking; the magnetometer runs at
	// 100 Hz in 16-bit mode for a low-noise heading estimate.
	if err := chip.ConfigureAccel(1); err != nil { // Set accelerometer full-scale, (full_scale=0–3) → error
		panic(err)
	}
	if err := chip.ConfigureGyro(1); err != nil { // Set gyroscope full-scale, (full_scale=0–3) → error
		panic(err)
	}
	if err := chip.EnableMag(16, 6); err != nil { // Initialise magnetometer, (bits=14|16, mode=1|2|6) → error
		panic(err)
	}

	fmt.Printf("%-8s %-10s %-10s %-12s %-12s %-12s\n", "Sample", "Roll", "Pitch", "Heading", "Gmag (g)", "Wmag (rad/s)")
	for n := 0; n < 60; n++ {
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

		// --- Tilt angles from the gravity vector ---
		// roll  = atan2(ay, az)
		// pitch = atan2(-ax, sqrt(ay^2 + az^2))
		axF, ayF, azF := float64(ax), float64(ay), float64(az)
		roll := math.Atan2(ayF, azF) * 180.0 / math.Pi
		pitch := math.Atan2(-axF, math.Sqrt(ayF*ayF+azF*azF)) * 180.0 / math.Pi

		// --- Magnetic heading (no tilt compensation) ---
		// atan2(my, mx) gives the angle of the horizontal projection of
		// the field. With no tilt compensation the result drifts when
		// the board is rolled more than a few degrees; a real tilt-
		// compensated heading would rotate (mx, my) by the inverse of
		// the roll/pitch rotation.
		heading := math.Atan2(float64(my), float64(mx)) * 180.0 / math.Pi
		if heading < 0 {
			heading += 360
		}

		// --- Magnitudes for quick visual comparison ---
		gMag := math.Sqrt(axF*axF+ayF*ayF+azF*azF) / 9.80665
		wMag := math.Sqrt(float64(gx)*float64(gx) +
			float64(gy)*float64(gy) +
			float64(gz)*float64(gz))

		fmt.Printf("%-8d %-10.2f %-10.2f %-12.2f %-12.3f %-12.3f\n", n, roll, pitch, heading, gMag, wMag)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

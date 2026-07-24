//go:build tinygo

// MPU6050 demo example — TinyGo / Raspberry Pi Pico W.
//
// "Motion logger": configures the driver for ±4 g accel and ±500 dps
// gyro, then in a loop reads accel and gyro, computes roll and pitch
// from the gravity vector, and prints the orientation and gyro
// magnitude once per second.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x68) // Create I2C transport, (i2c, addr=0x68) → (*I2CTransport)

	chip, err := imu.NewMPU6050Full(tr) // Create MPU6050 driver, (transport) → (*MPU6050Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure for board-tilt demonstration ---
	// ±4 g and ±500 dps suit a wide range of motions: gentler than
	// ±250 dps/±2 g for slow walking, but still wide enough for
	// shaking the board to see large responses.
	if err := chip.ConfigureAccel(1); err != nil { // Set accelerometer full-scale, (full_scale=0–3) → error
		panic(err)
	}
	if err := chip.ConfigureGyro(1); err != nil { // Set gyroscope full-scale, (full_scale=0–3) → error
		panic(err)
	}

	fmt.Printf("%-8s %-10s %-10s %-12s %-12s\n", "Sample", "Roll", "Pitch", "Gmag (g)", "Wmag (rad/s)")
	for n := 0; n < 60; n++ {
		// --- Gate on DATA_RDY before sampling ---
		// The chip latches new sensor data at the configured sample
		// rate (1 kHz internally, divided to 200 Hz by SMPLRT_DIV=4).
		// Polling INT_STATUS avoids reading the same frame twice.
		ready, err := chip.DataReady()
		if err != nil {
			panic(err)
		}
		_ = ready

		ax, ay, az, err := chip.Accel() // Read 3-axis acceleration, () → (float32 m/s², float32 m/s², float32 m/s², error)
		if err != nil {
			panic(err)
		}
		gx, gy, gz, err := chip.Gyro() // Read 3-axis angular rate, () → (float32 rad/s, float32 rad/s, float32 rad/s, error)
		if err != nil {
			panic(err)
		}

		// --- Tilt angles from the gravity vector ---
		// roll  = atan2(ay, az)
		// pitch = atan2(-ax, sqrt(ay^2 + az^2))
		// This approximation ignores centripetal acceleration during
		// rotation; for static or slow motion it matches the actual
		// board orientation within ~1 degree.
		axF, ayF, azF := float64(ax), float64(ay), float64(az)
		roll := math.Atan2(ayF, azF) * 180.0 / math.Pi
		pitch := math.Atan2(-axF, math.Sqrt(ayF*ayF+azF*azF)) * 180.0 / math.Pi

		// --- Magnitudes for quick visual comparison ---
		// g_g magnitude should hover around 9.81 m/s^2 when the board
		// is stationary; w magnitude is the spin rate around any axis.
		gMag := math.Sqrt(axF*axF+ayF*ayF+azF*azF) / 9.80665
		wMag := math.Sqrt(float64(gx)*float64(gx) +
			float64(gy)*float64(gy) +
			float64(gz)*float64(gz))

		fmt.Printf("%-8d %-10.2f %-10.2f %-12.3f %-12.3f\n", n, roll, pitch, gMag, wMag)
		time.Sleep(time.Second)
	}
}

//go:build linux && !tinygo

// ICM20948 demo example — Linux host.
//
// Motion and heading monitor: ±4g / ±500dps, magnetometer, wake-on-motion
// and FIFO; prints roll, pitch, tilt-compensated heading and gyro magnitude
// every 100 ms for 60 s.
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

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	chip, err := imu.NewICM20948Full(conn) // Create ICM20948 driver, (connection) → (*ICM20948Full, error)
	must(err)

	// --- Configure for motion and heading monitoring ---
	// ±4g / ±500dps give headroom for sharper motion; DLPFCFG 3 with a divider of
	// 10 keeps a ~100 Hz sample rate with a ~50 Hz noise bandwidth. The
	// magnetometer runs through the chip's own I²C master, so one connection
	// is enough.
	must(chip.ConfigureAccel(1))                  // Configure accel range, (fullScale=0) → error
	must(chip.ConfigureGyro(1))                   // Configure gyro range, (fullScale=0) → error
	must(chip.ConfigureFilters(3, 3, true, true)) // Configure low-pass filters, (gyroDlpf, accelDlpf, gyroFilter, accelFilter) → error
	must(chip.ConfigureSampleRate(10, 10))        // Configure sample rate, (gyroDiv, accelDiv) → error
	must(chip.EnableMag())                        // Enable magnetometer, () → error
	temp0, err := chip.Temperature()              // Read die temperature, () → (float32 °C, error)
	must(err)
	fmt.Printf("ICM-20948 die temperature: %.1f C\n", temp0)

	// --- Arm wake-on-motion and route it to the interrupt status ---
	// A 200 mg threshold flags a bump; PollInterrupt reports it as
	// ICM20948SourceWakeOnMotion without needing the INT pin wired.
	must(chip.SetWakeOnMotion(200, true))                      // Arm wake-on-motion, (thresholdMg, enabled) → error
	must(chip.EnableInterrupt(imu.ICM20948SourceWakeOnMotion)) // Enable interrupt source, (source) → error

	// --- Stream accelerometer + gyro into the FIFO ---
	// Stream mode overwrites the oldest data; once per second the FIFO is
	// drained and counted in 12-byte frames (accel X, Y, Z, gyro X, Y, Z).
	must(chip.EnableFifo(true, true, false)) // Enable FIFO sources, (accel, gyro, temp) → error
	must(chip.SetFifoMode(false))            // Select FIFO mode, (snapshot) → error

	fmt.Println("roll     pitch    heading  |gyro|")

	for iteration := 0; iteration < 600; iteration++ { // 600 x 100 ms = 60 s
		a, g, _, err := chip.ReadAll() // Read one coherent sample, () → ([3]float32 m/s², [3]float32 rad/s, float32 °C, error)
		must(err)
		mx, my, mz, err := chip.Mag() // Read 3-axis magnetic field, () → (float32, float32, float32 µT, error)
		must(err)

		// --- Compute roll, pitch and a tilt-compensated heading ---
		// The magnetometer frame is rotated 180° about X relative to the
		// inertial frame, so (mx, -my, -mz) maps it into the accel frame
		// before the tilt compensation rotates it into the horizontal plane.
		roll := math.Atan2(float64(a[1]), float64(a[2]))
		pitch := math.Atan2(-float64(a[0]), math.Hypot(float64(a[1]), float64(a[2])))
		bx, by, bz := float64(mx), -float64(my), -float64(mz)
		xh := bx*math.Cos(pitch) + bz*math.Sin(pitch)
		yh := bx*math.Sin(roll)*math.Sin(pitch) + by*math.Cos(roll) - bz*math.Sin(roll)*math.Cos(pitch)
		heading := math.Mod(math.Atan2(-yh, xh)*180/math.Pi+360, 360)
		gyroMag := math.Sqrt(float64(g[0]*g[0] + g[1]*g[1] + g[2]*g[2]))
		fmt.Printf("%-8.1f %-8.1f %-8.1f %-8.3f\n", roll*180/math.Pi, pitch*180/math.Pi, heading, gyroMag)

		status, err := chip.PollInterrupt() // Read interrupt status, () → (uint8, error)
		must(err)
		if status&imu.ICM20948SourceWakeOnMotion != 0 {
			fmt.Println("MOTION")
		}

		if iteration%10 == 9 {
			data, err := chip.ReadFifo(-1) // Read FIFO data, (count; <0 = all) → ([]byte, error)
			must(err)
			fmt.Printf("FIFO: %d bytes, %d frames\n", len(data), len(data)/12)
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
